#!/usr/bin/env python3
"""Fail when a Kotlin source under src/ is untracked by git (DEFERRED.md §4.7).

Why this exists. Twice now a commit has shipped call sites, stylesheets and i18n bundles for new
code whose *sources* it never staged, leaving a HEAD that does not compile from a clean checkout:

  - 2026-07-26: `i18n/I18n.kt`, `i18n/GameText.kt`, `i18n/Language.kt`, `rules/UnitCapabilities.kt`,
    `hero/HeroEventDisplay.kt` -- imported by 29 tracked files between them.
  - 2026-07-27: `ui/AttachmentPickerPresenter.kt`, `ui/UnitStatAttachmentMarks.kt`,
    `model/TerrainMovementCost.kt`, `rules/AttachmentPenalties.kt` -- called from
    `EquipmentUnitStrip`, `UnitStatCard`, `TerrainEx`, `AttackCalculation`, `MovementRules` and
    `SupplyRules`.

Both times the cause was `git commit -a`, which stages modifications and deletions and never
untracked files, and both times it went unnoticed because the working tree builds and tests green
either way -- nothing in this project builds from a fresh clone. Advice in a document did not
prevent the second occurrence, so the check now runs on the command everyone already runs.

Scope is deliberately narrow: `src/**/*.kt` only. `docs/`, `tools/`, scenario XML, generated art
and `resources/units/images/` stay untracked on purpose, and this must not start arguing with that
convention.
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE_DIR = "src"
SUFFIX = ".kt"


def untracked_kotlin_sources() -> list[str] | None:
    """Untracked `.kt` paths under src/, or None when git cannot answer (not a repo, no git)."""
    try:
        completed = subprocess.run(
            ["git", "status", "--porcelain", "--untracked-files=all", "--", SOURCE_DIR],
            cwd=ROOT,
            capture_output=True,
            text=True,
            encoding="utf-8",
            check=False,
        )
    except OSError:
        return None
    if completed.returncode != 0:
        return None
    found = []
    for line in completed.stdout.splitlines():
        # Porcelain v1: two status columns, a space, then the path. "??" is untracked.
        if not line.startswith("?? "):
            continue
        path = line[3:].strip().strip('"')
        if path.endswith(SUFFIX):
            found.append(path)
    return sorted(found)


def main() -> int:
    untracked = untracked_kotlin_sources()
    if untracked is None:
        # Not a git checkout (a source tarball, a vendored copy) -- nothing to verify, and failing
        # here would block builds that are legitimately outside version control.
        print("Tracked-source check skipped: git is unavailable or this is not a repository")
        return 0
    if untracked:
        # ASCII only in printed output: this runs under Gradle on a Windows console whose code page
        # cannot necessarily encode the section sign.
        print("Untracked Kotlin sources under src/ (DEFERRED.md 4.7):", file=sys.stderr)
        for path in untracked:
            print(f"  - {path}", file=sys.stderr)
        print(
            "\nHEAD will not compile from a clean checkout once anything tracked imports these.\n"
            "Run 'git add' on them, or move them out of src/ if they are not production sources.",
            file=sys.stderr,
        )
        return 1
    print("Tracked-source check passed: every .kt under src/ is tracked by git")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
