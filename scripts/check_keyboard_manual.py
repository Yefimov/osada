#!/usr/bin/env python3
"""Keeps `manual.html`'s Keyboard Shortcuts section honest against the game's command catalog.

`docs/design/keyboard-shortcuts-and-help.md` §7 requires a check like this: before the catalog
existed, the manual listed the inherited Panzer Marshal keys while the Kotlin UI handled only
Escape, so the manual promised keys the game did not have. This script fails the build if the two
ever drift again.

It compares, per F1-card row:

* the row's stable command id (`<li data-command="...">`), and
* the row's key cap (`<kbd>...</kbd>`), rendered exactly as the F1 card renders it.

The catalog is parsed straight out of `CommandCatalog.kt` rather than duplicated here, so the only
way to satisfy this check is to change the real source of truth.
"""

from __future__ import annotations

import html
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CATALOG = ROOT / "src/jsMain/kotlin/org/osada/ui/keyboard/CommandCatalog.kt"
MANUAL = ROOT / "src/jsMain/resources/manual.html"

CAP_JOIN = " / "

CONST_RE = re.compile(r'const val (\w+) = "([^"]+)"')
BINDING_RE = re.compile(r'ShortcutBinding\(\s*"([^"]+)"')
COMMAND_RE = re.compile(r"GameCommand\(\s*([A-Z_]+)\s*,", re.MULTILINE)
PAN_RE = re.compile(r'panCommand\(\s*([A-Z_]+)\s*,\s*"([^"]+)"')
SECTION_RE = re.compile(
    r'<section id="nav_keyboard">(.*?)</section>', re.DOTALL
)
ROW_RE = re.compile(
    r'<li data-command="([^"]+)"><kbd>(.*?)</kbd>', re.DOTALL
)


def _constants(text: str) -> dict[str, str]:
    return {name: value for name, value in CONST_RE.findall(text)}


def _catalog_rows() -> dict[str, str]:
    """`{card row id: key cap}`, mirroring `CommandCatalog.cardRows`."""
    text = CATALOG.read_text(encoding="utf-8")
    consts = _constants(text)
    rows: dict[str, list[str]] = {}
    order: list[str] = []

    def add(row_id: str, cap: str) -> None:
        if row_id not in rows:
            rows[row_id] = []
            order.append(row_id)
        rows[row_id].append(cap)

    # Every pan direction collapses onto the shared `pan` row, exactly as the card does.
    pan_id = consts.get("PAN", "pan")
    for const, cap in PAN_RE.findall(text):
        add(pan_id, cap)

    # Each GameCommand(...) call owns every ShortcutBinding up to the next GameCommand/panCommand.
    starts = [(m.start(), m.group(1)) for m in COMMAND_RE.finditer(text)]
    for index, (start, const) in enumerate(starts):
        end = starts[index + 1][0] if index + 1 < len(starts) else len(text)
        command_id = consts.get(const)
        if command_id is None:
            continue
        for cap in BINDING_RE.findall(text[start:end]):
            add(command_id, cap)

    return {row_id: CAP_JOIN.join(rows[row_id]) for row_id in order}


def _manual_rows() -> dict[str, str]:
    text = MANUAL.read_text(encoding="utf-8")
    section = SECTION_RE.search(text)
    if section is None:
        raise SystemExit("manual.html has no <section id=\"nav_keyboard\"> shortcuts section")
    return {
        command_id: html.unescape(cap).strip()
        for command_id, cap in ROW_RE.findall(section.group(1))
    }


def main() -> int:
    catalog = _catalog_rows()
    manual = _manual_rows()
    problems: list[str] = []

    if not catalog:
        problems.append("could not parse any command out of CommandCatalog.kt")

    for command_id, cap in catalog.items():
        if command_id not in manual:
            problems.append(f"manual.html is missing the '{command_id}' row ({cap})")
        elif manual[command_id] != cap:
            problems.append(
                f"'{command_id}': manual says '{manual[command_id]}', catalog says '{cap}'"
            )

    for command_id in manual:
        if command_id not in catalog:
            problems.append(
                f"manual.html documents '{command_id}', which the command catalog does not bind"
            )

    if problems:
        print("Keyboard manual check FAILED:")
        for problem in problems:
            print(f"  ! {problem}")
        return 1

    print(f"Keyboard manual check passed: {len(catalog)} command rows match the catalog")
    return 0


if __name__ == "__main__":
    sys.exit(main())
