from __future__ import annotations

import argparse
import json
import os
import shutil
import sys
import tempfile
from pathlib import Path
from typing import Any


DEFAULT_ROOT = Path(r"C:\Users\Илья\IdeaProjects\osada")

# Генерируемые, служебные и сторонние каталоги.
EXCLUDED_DIRECTORIES = {
    ".git",
    ".gradle",
    ".idea",
    ".kotlin",
    ".yarn",
    "build",
    "coverage",
    "dist",
    "node_modules",
    "out",
}

# Lock-файлы генерируются пакетным менеджером.
EXCLUDED_FILES = {
    "package-lock.json",
}


class DuplicateKeyError(ValueError):
    """Raised when a JSON object contains the same key more than once."""


def reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    """
    Preserve original key order and reject duplicate keys.

    Standard json.loads() silently keeps only the last duplicate key,
    which could damage an existing file during formatting.
    """
    result: dict[str, Any] = {}

    for key, value in pairs:
        if key in result:
            raise DuplicateKeyError(f"Duplicate JSON key: {key!r}")
        result[key] = value

    return result


def reject_non_standard_number(value: str) -> None:
    """
    Reject NaN, Infinity and -Infinity.

    They are accepted by Python's parser but are not valid standard JSON.
    """
    raise ValueError(f"Non-standard JSON numeric value: {value}")


def iter_json_files(
    root: Path,
    include_lockfiles: bool,
) -> list[Path]:
    """Return JSON files while pruning generated directories."""
    files: list[Path] = []

    for current_root, directory_names, file_names in os.walk(
        root,
        followlinks=False,
    ):
        directory_names[:] = sorted(
            name
            for name in directory_names
            if name not in EXCLUDED_DIRECTORIES
        )

        current_path = Path(current_root)

        for file_name in sorted(file_names):
            if not file_name.lower().endswith(".json"):
                continue

            if not include_lockfiles and file_name in EXCLUDED_FILES:
                continue

            files.append(current_path / file_name)

    return files


def read_json(path: Path) -> tuple[str, Any]:
    """
    Read UTF-8 JSON.

    utf-8-sig also accepts files that currently contain a UTF-8 BOM.
    Rewritten files will use ordinary UTF-8 without BOM.
    """
    text = path.read_text(encoding="utf-8-sig")

    data = json.loads(
        text,
        object_pairs_hook=reject_duplicate_keys,
        parse_constant=reject_non_standard_number,
    )

    return text, data


def format_json(data: Any, indent: int) -> str:
    """Produce deterministic, readable UTF-8 JSON."""
    return (
        json.dumps(
            data,
            ensure_ascii=False,
            allow_nan=False,
            indent=indent,
            sort_keys=False,
        )
        + "\n"
    )


def atomic_write(path: Path, content: str) -> None:
    """Replace a file atomically without leaving a partially written JSON."""
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{path.name}.",
        suffix=".tmp",
        dir=path.parent,
        text=True,
    )
    temporary_path = Path(temporary_name)

    try:
        with os.fdopen(
            descriptor,
            mode="w",
            encoding="utf-8",
            newline="\n",
        ) as temporary_file:
            temporary_file.write(content)
            temporary_file.flush()
            os.fsync(temporary_file.fileno())

        shutil.copymode(path, temporary_path)
        os.replace(temporary_path, path)
    except Exception:
        temporary_path.unlink(missing_ok=True)
        raise


def relative_display(path: Path, root: Path) -> str:
    try:
        return str(path.relative_to(root))
    except ValueError:
        return str(path)


def process_file(
    path: Path,
    root: Path,
    indent: int,
    write: bool,
    max_size_bytes: int | None,
) -> str:
    if max_size_bytes is not None and path.stat().st_size > max_size_bytes:
        return "too_large"

    try:
        original_text, data = read_json(path)
    except (UnicodeDecodeError, json.JSONDecodeError, DuplicateKeyError, ValueError) as error:
        print(
            f"[INVALID] {relative_display(path, root)}\n"
            f"          {error}",
            file=sys.stderr,
        )
        return "invalid"

    formatted_text = format_json(data, indent)

    if original_text == formatted_text:
        return "unchanged"

    if write:
        atomic_write(path, formatted_text)
        print(f"[FORMATTED] {relative_display(path, root)}")
    else:
        print(f"[WOULD FORMAT] {relative_display(path, root)}")

    return "changed"


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Recursively format JSON files while excluding generated "
            "and third-party directories."
        ),
    )

    parser.add_argument(
        "--root",
        type=Path,
        default=DEFAULT_ROOT,
        help=f"Project root. Default: {DEFAULT_ROOT}",
    )
    parser.add_argument(
        "--write",
        action="store_true",
        help="Actually rewrite files. Without this flag, only report changes.",
    )
    parser.add_argument(
        "--indent",
        type=int,
        default=2,
        choices=range(1, 9),
        metavar="N",
        help="Indentation width from 1 to 8 spaces. Default: 2.",
    )
    parser.add_argument(
        "--max-size-mb",
        type=float,
        default=100.0,
        help=(
            "Skip files larger than this many MiB. "
            "Use 0 to disable the limit. Default: 100."
        ),
    )
    parser.add_argument(
        "--include-lockfiles",
        action="store_true",
        help="Also format package-lock.json files.",
    )

    return parser.parse_args()


def main() -> int:
    args = parse_arguments()
    root = args.root.resolve()

    if not root.is_dir():
        print(f"Project directory does not exist: {root}", file=sys.stderr)
        return 2

    if args.max_size_mb < 0:
        print("--max-size-mb cannot be negative.", file=sys.stderr)
        return 2

    max_size_bytes = (
        None
        if args.max_size_mb == 0
        else int(args.max_size_mb * 1024 * 1024)
    )

    json_files = iter_json_files(
        root=root,
        include_lockfiles=args.include_lockfiles,
    )

    counters = {
        "changed": 0,
        "unchanged": 0,
        "invalid": 0,
        "too_large": 0,
    }

    for path in json_files:
        result = process_file(
            path=path,
            root=root,
            indent=args.indent,
            write=args.write,
            max_size_bytes=max_size_bytes,
        )
        counters[result] += 1

    mode = "WRITE" if args.write else "DRY RUN"

    print()
    print(f"Mode:       {mode}")
    print(f"Found:      {len(json_files)} JSON files")
    print(f"Changed:    {counters['changed']}")
    print(f"Unchanged:  {counters['unchanged']}")
    print(f"Invalid:    {counters['invalid']}")
    print(f"Too large:  {counters['too_large']}")

    if not args.write and counters["changed"] > 0:
        print()
        print("Run again with --write to apply the changes.")

    return 1 if counters["invalid"] > 0 else 0


if __name__ == "__main__":
    raise SystemExit(main())
