#!/usr/bin/env python3
"""Keeps the ruleset catalogue honest against the engine and the string bundles.

`docs/design/ruleset-profiles.md` §2 is the whole point of this check: every rule the Rules window
offers must steer a branch OSADA already executes. A key with no live call site would turn that
window into a list of promises, which §10 names as an explicit non-goal.

Three things are checked for every `RuleKey`:

  * a live engine read — some Kotlin outside the ruleset package resolves it through
    `ActiveRuleset` or reads the resolved value, so choosing it actually changes the game;
  * canonical English and complete Russian `label`/`help` strings, since the window shows both;
  * for a rule with an `equip.cfg` name, that no rule code still reads that key straight from
    `EfileConfig`, which would bypass the player's selection entirely.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RULESET_DIR = ROOT / "src/jsMain/kotlin/org/osada/rules/ruleset"
CATALOG = RULESET_DIR / "Ruleset.kt"
KOTLIN_ROOT = ROOT / "src/jsMain/kotlin"
BUNDLES = {
    "en": ROOT / "src/jsMain/resources/i18n/en/ui.json",
    "ru": ROOT / "src/jsMain/resources/i18n/ru/ui.json",
}

# `AA_INTERCEPT_MODE("aa_intercept_mode", "g2a_intercept_mode", 0, 3),`
RULE_RE = re.compile(
    r'^\s{4}([A-Z_]+)\("([a-z0-9_]+)",\s*(?:"([a-z0-9_]+)"|null),', re.MULTILINE
)


def _rules() -> list[tuple[str, str, str | None]]:
    text = CATALOG.read_text(encoding="utf-8")
    return [(m.group(1), m.group(2), m.group(3)) for m in RULE_RE.finditer(text)]


def _engine_sources() -> dict[Path, str]:
    sources: dict[Path, str] = {}
    for path in KOTLIN_ROOT.rglob("*.kt"):
        if RULESET_DIR in path.parents:
            continue
        sources[path] = path.read_text(encoding="utf-8", errors="replace")
    return sources


def main() -> int:
    rules = _rules()
    problems: list[str] = []
    if not rules:
        print("Ruleset key check FAILED:\n  ! could not parse any RuleKey out of Ruleset.kt")
        return 1

    sources = _engine_sources()
    bundles = {name: json.loads(path.read_text(encoding="utf-8")) for name, path in BUNDLES.items()}

    for constant, key, efile_key in rules:
        used_by = [
            path
            for path, text in sources.items()
            if f"RuleKey.{constant}" in text
        ]
        # The Rules window naturally mentions every key; a rule needs a consumer beyond the UI.
        engine_uses = [p for p in used_by if "/ui/" not in p.as_posix()]
        if not engine_uses:
            problems.append(
                f"'{key}' has no live engine read: nothing outside the UI resolves RuleKey.{constant}"
            )

        for locale, bundle in bundles.items():
            for suffix in ("label", "help"):
                if f"rules.{key}.{suffix}" not in bundle:
                    problems.append(f"'{key}' has no {locale} '{suffix}' string")

        if efile_key:
            bypassing = [
                path.relative_to(ROOT).as_posix()
                for path, text in sources.items()
                if f'EfileConfig.intKey("{efile_key}"' in text
            ]
            if bypassing:
                problems.append(
                    f"'{key}' is still read straight from EfileConfig in {', '.join(bypassing)}; "
                    "that bypasses the player's ruleset selection"
                )

    if problems:
        print("Ruleset key check FAILED:")
        for problem in problems:
            print(f"  ! {problem}")
        return 1

    print(f"Ruleset key check passed: {len(rules)} rules, each with a live engine read and en/ru copy")
    return 0


if __name__ == "__main__":
    sys.exit(main())
