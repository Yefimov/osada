#!/usr/bin/env python3
"""Validate OSADA runtime localization bundles and stable-key usage."""

from __future__ import annotations

import json
import re
import sys
from collections import Counter
from pathlib import Path
from typing import Any

from extract_story_dialogue_i18n import expected_catalogs

ROOT = Path(__file__).resolve().parents[1]
I18N_ROOT = ROOT / "src" / "jsMain" / "resources" / "i18n"
KOTLIN_ROOT = ROOT / "src" / "jsMain" / "kotlin"
PLURAL_CATEGORIES = {"zero", "one", "two", "few", "many", "other"}
PLACEHOLDER_RE = re.compile(r"\{([A-Za-z][A-Za-z0-9_]*)\}")
NUMBER_RE = re.compile(r"\d+(?:[ \u00a0,.]\d{3})*")
HTML_TAG_RE = re.compile(r"</?[A-Za-z][^>]*>")
CALL_RE = re.compile(r'I18n\.(?:t|plural|select)\(\s*(?:key\s*=\s*)?"([a-z0-9_.-]+)"')
# Keys that never appear beside an I18n call: EquipmentAbilityCatalog stores each ability's key as
# a plain string in a table and resolves it much later (`I18n.t(it)` over `abilityCatalogKeys()`),
# so CALL_RE cannot see them. Five of them shipped with no en/ru entry at all and the badge tooltip
# showed the raw key -- reported as *"`-AI` has no description, just `no_ai_buy`"*. These namespaces
# are table-driven by construction, so every literal in them is checked directly.
TABLE_KEY_RE = re.compile(r'"((?:equipment\.(?:ability|mechanics)|hud\.objective\.rule)\.[a-z0-9_]+)"')
REQUIRED_COMPLETE_LOCALE_PREFIXES = ("mobile.", "settings.mobile.", "settings.section.mobile.")
REQUIRED_COMPLETE_LOCALES = ("en", "ru")
MIN_STORY_TRANSLATION_LENGTH_RATIO = 0.35


class DuplicateKeyError(ValueError):
    pass


def unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise DuplicateKeyError(f"duplicate key: {key}")
        result[key] = value
    return result


def load_json(path: Path, errors: list[str]) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_object)
    except (OSError, UnicodeError, json.JSONDecodeError, DuplicateKeyError) as exc:
        errors.append(f"{path.relative_to(ROOT)}: {exc}")
        return {}
    if not isinstance(value, dict):
        errors.append(f"{path.relative_to(ROOT)}: root must be a JSON object")
        return {}
    return value


def placeholders(text: str) -> Counter[str]:
    return Counter(PLACEHOLDER_RE.findall(text))


def numbers(text: str) -> Counter[str]:
    return Counter(re.sub(r"[ \u00a0,.]", "", match.group()) for match in NUMBER_RE.finditer(text))


def number_is_present(number: str, translated: Counter[str], translated_text: str) -> bool:
    if translated[number] > 0:
        translated[number] -= 1
        return True
    # Authored year ranges sometimes abbreviate their second year (1943-44), while Russian spells
    # it out (1943–1944). This changes formatting, not the quantity.
    if len(number) == 2:
        expanded = f"19{number}"
        if translated[expanded] > 0:
            translated[expanded] -= 1
            return True
    # The November Revolution source names the Council of 21 with digits; the established Russian
    # rendering writes the same number as words.
    if number == "21" and re.search(r"\bдвадцат(?:и|ь)\s+одн", translated_text.lower()):
        return True
    return False


def is_authored_content(path: Path, key: str) -> bool:
    relative = path.relative_to(I18N_ROOT)
    return (
        any(part in {"briefings", "campaigns", "scenarios"} for part in relative.parts)
        or key.startswith("campaign.")
    )


def validate_content_fidelity(path: Path, key: str, english: str, translated: str, errors: list[str]) -> None:
    if not is_authored_content(path, key) or translated == "":
        return
    label = f"{path.relative_to(ROOT)}:{key}"
    translated_numbers = numbers(translated)
    missing_numbers = [
        number
        for number, count in numbers(english).items()
        for _ in range(count)
        if not number_is_present(number, translated_numbers, translated)
    ]
    if missing_numbers:
        errors.append(f"{label}: source numeric values are missing: {missing_numbers}")
    if Counter(HTML_TAG_RE.findall(english)) != Counter(HTML_TAG_RE.findall(translated)):
        errors.append(f"{label}: HTML tag multiplicity differs from English")
    if english.count("\n") != translated.count("\n"):
        errors.append(
            f"{label}: parsed newline count differs; English={english.count(chr(10))}, "
            f"translation={translated.count(chr(10))}"
        )


def leaf_for_branch(value: Any, branch: str) -> str | None:
    if isinstance(value, str):
        return value
    if not isinstance(value, dict):
        return None
    candidate = value.get(branch, value.get("other"))
    return candidate if isinstance(candidate, str) else None


def validate_value(path: Path, key: str, english: Any, translated: Any, errors: list[str]) -> None:
    label = f"{path.relative_to(ROOT)}:{key}"
    if isinstance(english, str):
        if not isinstance(translated, str):
            errors.append(f"{label}: translation type differs from English string")
            return
        if translated == "":
            return
        if placeholders(english) != placeholders(translated):
            errors.append(
                f"{label}: placeholder multiplicity differs; English={dict(placeholders(english))}, "
                f"translation={dict(placeholders(translated))}"
            )
        validate_content_fidelity(path, key, english, translated, errors)
        return

    if not isinstance(english, dict) or not isinstance(translated, dict):
        errors.append(f"{label}: values must both be strings or branch objects")
        return
    if "other" not in english or "other" not in translated:
        errors.append(f"{label}: branch objects must contain an 'other' value")
    english_keys = set(english)
    translated_keys = set(translated)
    if english_keys <= PLURAL_CATEGORIES and not translated_keys <= PLURAL_CATEGORIES:
        errors.append(f"{label}: plural object contains unsupported categories {sorted(translated_keys - PLURAL_CATEGORIES)}")

    for branch, translated_text in translated.items():
        if not isinstance(translated_text, str):
            errors.append(f"{label}.{branch}: branch value must be a string")
            continue
        english_text = leaf_for_branch(english, branch)
        if english_text is None:
            errors.append(f"{label}.{branch}: no matching English branch or English 'other' fallback")
            continue
        if translated_text == "":
            continue
        if placeholders(english_text) != placeholders(translated_text):
            errors.append(
                f"{label}.{branch}: placeholder multiplicity differs; "
                f"English={dict(placeholders(english_text))}, "
                f"translation={dict(placeholders(translated_text))}"
            )
        validate_content_fidelity(path, f"{key}.{branch}", english_text, translated_text, errors)


def check_gendered_branches(path: Path, key: str, value: Any, errors: list[str]) -> None:
    """Every selector in a gendered branch object needs its feminine sibling (DEFERRED.md §4.16).

    `HeroBiographyNarrator` picks the branch `"<selector>_f"` for a female officer, and
    `I18n.branchValue` resolves a missing branch by falling back to `"other"` — which in these
    objects is the MASCULINE sentence. So a selector added without its `_f` sibling does not throw,
    does not log, and does not reach the English bundle: it silently narrates a woman's biography in
    the masculine, which is exactly the bug §4.11 was filed to remove.

    The rule is inferred, not configured: an object is "gendered" once any branch ends in `_f`, so
    a new gendered key is covered the moment its first feminine branch is written.
    """
    if not isinstance(value, dict):
        return
    branches = {branch for branch, text in value.items() if isinstance(text, str)}
    if not any(branch.endswith("_f") for branch in branches):
        return
    for branch in sorted(branches):
        if branch == "other" or branch.endswith("_f"):
            continue
        if f"{branch}_f" not in branches:
            errors.append(
                f"{path.relative_to(ROOT)}:{key}.{branch}: gendered branch object is missing "
                f"'{branch}_f'; a missing feminine branch falls back to 'other' (masculine) in "
                f"silence — see DEFERRED.md §4.16"
            )


def bundle_files(language: str) -> dict[Path, Path]:
    root = I18N_ROOT / language
    if not root.exists():
        return {}
    return {path.relative_to(root): path for path in root.rglob("*.json")}


def used_ui_keys() -> set[str]:
    keys: set[str] = set()
    for path in KOTLIN_ROOT.rglob("*.kt"):
        text = path.read_text(encoding="utf-8")
        keys.update(CALL_RE.findall(text))
        keys.update(TABLE_KEY_RE.findall(text))
    return keys


def check_story_dialogues(
    english_bundles: dict[Path, dict[str, Any]],
    errors: list[str],
) -> None:
    """Keep authored dialogue, generated English catalogs, and complete Russian overlays aligned."""
    for relative, expected in expected_catalogs().items():
        english = english_bundles.get(relative)
        english_path = I18N_ROOT / "en" / relative
        russian_path = I18N_ROOT / "ru" / relative
        if english != expected:
            errors.append(
                f"{english_path.relative_to(ROOT)}: differs from authored campaign dialogue; "
                "run scripts/extract_story_dialogue_i18n.py --write-en"
            )
            continue
        if not russian_path.exists():
            errors.append(f"{russian_path.relative_to(ROOT)}: required Russian story bundle is missing")
            continue
        russian = load_json(russian_path, errors)
        missing = set(expected) - set(russian)
        extra = set(russian) - set(expected)
        for key in sorted(missing):
            errors.append(f"{russian_path.relative_to(ROOT)}:{key}: required Russian story text is missing")
        for key in sorted(extra):
            errors.append(f"{russian_path.relative_to(ROOT)}:{key}: key does not exist in authored dialogue")
        for key in sorted(set(expected) & set(russian)):
            translated = russian[key]
            if not isinstance(translated, str) or not translated.strip():
                errors.append(f"{russian_path.relative_to(ROOT)}:{key}: Russian story text is blank")
                continue
            if len(translated) < len(expected[key]) * MIN_STORY_TRANSLATION_LENGTH_RATIO:
                errors.append(
                    f"{russian_path.relative_to(ROOT)}:{key}: translation is suspiciously short "
                    f"({len(translated)} vs {len(expected[key])} source characters)"
                )


def main() -> int:
    errors: list[str] = []
    english_files = bundle_files("en")
    if Path("ui.json") not in english_files:
        errors.append("src/jsMain/resources/i18n/en/ui.json: canonical UI bundle is missing")

    english_bundles = {relative: load_json(path, errors) for relative, path in english_files.items()}
    for relative, bundle in english_bundles.items():
        for key in sorted(bundle):
            check_gendered_branches(english_files[relative], key, bundle[key], errors)
    for language_dir in sorted(path for path in I18N_ROOT.iterdir() if path.is_dir() and path.name != "en"):
        for relative, translated_path in bundle_files(language_dir.name).items():
            english = english_bundles.get(relative)
            if english is None:
                errors.append(f"{translated_path.relative_to(ROOT)}: no matching English bundle")
                continue
            translated = load_json(translated_path, errors)
            for key in sorted(set(translated) - set(english)):
                errors.append(f"{translated_path.relative_to(ROOT)}:{key}: key does not exist in English")
            for key in sorted(set(translated) & set(english)):
                validate_value(translated_path, key, english[key], translated[key], errors)
                # Checked per bundle, not only against English: a `_f` branch present in `en` but
                # missing in `ru` still resolves against RU's own masculine `other` before the
                # English bundle is ever consulted (§4.16).
                check_gendered_branches(translated_path, key, translated[key], errors)

    english_ui = english_bundles.get(Path("ui.json"), {})
    check_story_dialogues(english_bundles, errors)
    required_complete_locale_keys = {
        key for key in english_ui if key.startswith(REQUIRED_COMPLETE_LOCALE_PREFIXES)
    }
    for language in REQUIRED_COMPLETE_LOCALES:
        language_dir = I18N_ROOT / language
        ui_path = language_dir / "ui.json"
        translated_ui = load_json(ui_path, errors) if ui_path.exists() else {}
        for key in sorted(required_complete_locale_keys):
            value = translated_ui.get(key)
            if value is None:
                errors.append(f"{ui_path.relative_to(ROOT)}:{key}: required active-locale mobile key is missing")
            elif isinstance(value, str) and value == "":
                errors.append(f"{ui_path.relative_to(ROOT)}:{key}: required active-locale mobile translation is blank")

    for key in sorted(used_ui_keys() - set(english_ui)):
        errors.append(f"Kotlin source uses i18n key missing from en/ui.json: {key}")

    if errors:
        print("Translation validation failed:", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 1

    translated_count = sum(len(load_json(path, [])) for path in bundle_files("ru").values())
    talysh_count = sum(len(load_json(path, [])) for path in bundle_files("tly").values())
    print(
        f"Translation validation passed: {len(english_ui)} English UI keys, "
        f"{translated_count} Russian entries; frozen Talysh overlay has {talysh_count} entries "
        "and is not completeness-gated"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
