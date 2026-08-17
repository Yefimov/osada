#!/usr/bin/env python3
"""Validates the authorship sidecar and keeps credits out of description prose.

`docs/player-comfort-roadmap.md`'s authorship contract: campaign/scenario credits live in a
hand-authored sidecar keyed by stable file id, never as an `Author:` sentence inside the
player-facing synopsis and never inside the generated positional arrays that a re-import rewrites.

Two things are checked:

  * every sidecar key names a campaign/scenario file that actually exists, and every entry has a
    non-empty name and a known role -- a credit pointing at nothing credits nobody;
  * no shipped description still carries an embedded credit line. Descriptions are localizable
    synopsis text; proper names inside them would be dragged through every translation pass, and
    the dedicated Author row would silently duplicate them.

The generated list files are checked by pattern rather than parsed: they are JS array literals
whose exact shape is the importer's business, not this script's.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "src/jsMain/resources/resources"
SIDECAR = RES / "credits/authors.json"
CAMPAIGN_LIST = RES / "campaigns/campaignlist.js"
SCENARIO_LIST = RES / "scenarios/scenariolist.js"
CAMPAIGN_DIR = RES / "campaigns/data"
SCENARIO_DIR = RES / "scenarios/data"

ROLES = {"original", "conversion", "adaptation", "translation"}

# "Author: X", "Author = X", "Original author - X". Deliberately narrow: the word "authority"
# appears in a dozen legitimate briefings and must not trip this.
CREDIT_RE = re.compile(r"\b(?:original\s+)?authors?\s*[:=]\s*\S", re.IGNORECASE)


def _known_files() -> set[str]:
    files: set[str] = set()
    for directory in (CAMPAIGN_DIR, SCENARIO_DIR):
        if directory.is_dir():
            files.update(path.name for path in directory.iterdir() if path.is_file())
    return files


def _check_sidecar(problems: list[str]) -> None:
    if not SIDECAR.exists():
        problems.append(f"{SIDECAR.relative_to(ROOT)} is missing")
        return
    try:
        data = json.loads(SIDECAR.read_text(encoding="utf-8"))
    except ValueError as exc:
        problems.append(f"{SIDECAR.name} is not valid JSON: {exc}")
        return

    known = _known_files()
    entries = data.get("entries")
    if not isinstance(entries, dict):
        problems.append(f"{SIDECAR.name} has no 'entries' object")
        return

    for file_id, credits in sorted(entries.items()):
        if known and file_id not in known:
            problems.append(f"credits reference '{file_id}', which is not a shipped campaign or scenario")
        if not isinstance(credits, list) or not credits:
            problems.append(f"'{file_id}' has no credit entries")
            continue
        for credit in credits:
            if not isinstance(credit, dict) or not str(credit.get("name", "")).strip():
                problems.append(f"'{file_id}' has a credit with no name")
            role = credit.get("role") if isinstance(credit, dict) else None
            if role is not None and role not in ROLES:
                problems.append(f"'{file_id}' credit role '{role}' is not one of {sorted(ROLES)}")


def _check_prose(problems: list[str]) -> None:
    for path in (CAMPAIGN_LIST, SCENARIO_LIST):
        if not path.exists():
            continue
        for match in CREDIT_RE.finditer(path.read_text(encoding="utf-8", errors="replace")):
            snippet = match.group(0).strip()
            problems.append(
                f"{path.name} still embeds a credit in description prose ('{snippet}...'). "
                "Move it into resources/credits/authors.json instead."
            )


def main() -> int:
    problems: list[str] = []
    _check_sidecar(problems)
    _check_prose(problems)

    if problems:
        print("Author credits check FAILED:")
        for problem in problems:
            print(f"  ! {problem}")
        return 1

    entries = json.loads(SIDECAR.read_text(encoding="utf-8")).get("entries", {})
    print(f"Author credits check passed: {len(entries)} credited file(s), no credits left in prose")
    return 0


if __name__ == "__main__":
    sys.exit(main())
