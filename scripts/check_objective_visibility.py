#!/usr/bin/env python3
"""Locks the objective-visibility audit the objectives rail is built on.

`docs/design/action-affordances-and-objectives.md` §8 separates three things the scenario model
lumps together:

  * victory objective   - `victory` set AND a `flag`: required, and shown to the player;
  * optional capture    - a `flag` with no `victory`: prestige/score only;
  * hidden victory hex  - `victory` set with NO `flag`: a real win objective the player is never
                          shown outside Observer Mode.

The rail's fog discipline depends entirely on that third category staying rare and known. This
check re-runs the 2026-08-15 resource audit on every build so a future import cannot quietly add
hidden win conditions, or quietly delete the ones the design already accounts for.

It is a LOCK, not a rule: a change here is not automatically wrong. If an import legitimately moves
the numbers, re-read the affected scenarios, update the design's §8 audit and update the constants
below in the same change.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCENARIOS = ROOT / "src/jsMain/resources/resources/scenarios/data"

HEX_RE = re.compile(r"<hex[^>]*")

# The 2026-08-15 audit recorded in docs/design/action-affordances-and-objectives.md §8.
EXPECTED_HIDDEN_HEXES = 52
EXPECTED_HIDDEN_FILES = 30

# Operation Uranus is the design's worked example: three victory hexes, all flagged, none hidden.
LOCKED = {"rcampx01.xml": {"visible": 3, "hidden": 0}}


def counts(text: str) -> tuple[int, int]:
    visible = hidden = 0
    for tag in HEX_RE.findall(text):
        if "victory=" not in tag:
            continue
        if "flag=" in tag:
            visible += 1
        else:
            hidden += 1
    return visible, hidden


def main() -> int:
    if not SCENARIOS.is_dir():
        print("objective visibility check: no scenario directory, nothing to verify")
        return 0

    problems: list[str] = []
    hidden_total = 0
    hidden_files = 0

    for path in sorted(SCENARIOS.glob("*.xml")):
        visible, hidden = counts(path.read_text(encoding="utf-8", errors="replace"))
        if hidden:
            hidden_total += hidden
            hidden_files += 1
        locked = LOCKED.get(path.name)
        if locked is None:
            continue
        if visible != locked["visible"] or hidden != locked["hidden"]:
            problems.append(
                f"{path.name}: expected {locked['visible']} visible / {locked['hidden']} hidden "
                f"victory hexes, found {visible} / {hidden}"
            )

    if hidden_total != EXPECTED_HIDDEN_HEXES or hidden_files != EXPECTED_HIDDEN_FILES:
        problems.append(
            f"flag-less victory hexes: expected {EXPECTED_HIDDEN_HEXES} in "
            f"{EXPECTED_HIDDEN_FILES} scenarios, found {hidden_total} in {hidden_files}. "
            "Re-read the changed scenarios and update §8 of "
            "docs/design/action-affordances-and-objectives.md together with this script."
        )

    if problems:
        print("Objective visibility check FAILED:")
        for problem in problems:
            print(f"  ! {problem}")
        return 1

    print(
        f"Objective visibility check passed: {hidden_total} hidden victory hexes in "
        f"{hidden_files} scenarios, locks intact"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
