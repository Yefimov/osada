#!/usr/bin/env python3
"""Repair out-of-enum `uclass` values in the deployed equipment JSON.

## Why

`tools/og-import/csv_to_eqp.py` remaps OG's 24-wide class enum onto PM's 22-wide one. Its tail
was guessed rather than read from OG's own `[classes]` section, so **OG class 23 had no entry at
all** and `.get(og_cls, og_cls)` let the raw 23 through into the shipped JSON.

PM has no class 23. `UnitPredicates.isSea` accepts 14..21 and `isGround` accepts <=9, so a class-23
record was neither naval nor ground -- and `unitEntrenchRate[23]` threw `IndexOutOfBoundsException`
out of `canEntrench`, which `unitEndTurn` calls for every unit at every turn change. That escaped
the AI turn loop and left "Computer turn complete" stuck on screen (Willhelmshafen, turn 2).

## Why only the naval ones

OG's slot 23 has NO fixed meaning -- each efile names it in its own `[classes]` section and fills it
with whatever it likes. The canonical majority (OPENPG, OoB, ROI, OLGWW2, OLGCW, GCE, CC59, AO) call
it **Light Cruiser**, and `EFILE_KAISER` calls it "Auxiliary Ship" and fills it with Gunboat, River
Fleet, Auxiliary Cruiser, Hospital Ship, Monitor Support and Steam Gunboat -- all genuinely naval,
and PM's `LIGHT_CRUISER` (21) is the same slot, inside `isSea`. But `EFILE_BASEKORP` and
`EFILE_NOKORP` name it **"Special Units"** and park marker/effect records there (`eqp-united` carries
one called `.Rockets`: cost 1, 0 movement points, LEG movement). Remapping those to a warship class
would be a worse lie than leaving them unclassified.

So the movement method decides: a record is migrated only if it moves like a ship. Everything else
keeps `uclass = 23`, which is now harmless -- `Constants.entrenchRateFor` and `GameText.unitClass`
both tolerate an out-of-enum class, and `isSea`/`isGround` correctly decline to claim a marker
record. Same reasoning for the two `uclass = 82` records (`eqp-atomic`, `eqp-united`): outside OG's
enum entirely, no slot to map them to, left alone.

## What this deliberately does NOT touch

The same guessed tail also sent OG 21 (Battle Cruiser) to PM 20 and OG 22 (Cruiser) to PM 15
(Destroyer). Those are *mislabelled*, not broken -- 20 and 15 are both inside `isSea`, so every
rule still applies. They also cannot be inverted here: PM 15 conflates OG 17 and OG 22, so telling
a genuine Destroyer from a demoted Cruiser needs the source CSV. Correct values will come from the
next re-import through the now-fixed `OG2PM_CLASS`; see `tools/og-import/DEFERRED.md`.

Idempotent: re-running finds nothing to change.
"""
import io
import json
import os
import sys

STRAY_CLASS = 23
NAVAL_CLASS = 21  # PM UnitClass.LIGHT_CRUISER
# Constants.kt MovMethod: DEEP_NAVAL(6), COASTAL(7), AMPHIBIOUS(9), NAVAL(10).
NAVAL_MOVEMENT = {6, 7, 9, 10}
BASE = os.path.join("src", "jsMain", "resources", "resources", "equipment")


def fix_file(path):
    with io.open(path, encoding="utf-8") as fh:
        data = json.load(fh)

    hints = data.get("parsehints")
    if not hints or "uclass" not in hints or "movmethod" not in hints:
        return 0
    class_slot = hints.index("uclass")
    move_slot = hints.index("movmethod")

    migrated_ids = set()
    for eqid, record in (data.get("units") or {}).items():
        if not isinstance(record, list) or len(record) <= max(class_slot, move_slot):
            continue
        if record[class_slot] == STRAY_CLASS and record[move_slot] in NAVAL_MOVEMENT:
            record[class_slot] = NAVAL_CLASS
            migrated_ids.add(int(eqid))

    # indexes.unitclass is the by-class lookup the loader builds its country index from; it has to
    # move with the records or the two disagree about what a unit is.
    index = data.get("indexes", {}).get("unitclass")
    if isinstance(index, dict) and migrated_ids:
        stray = [i for i in index.get(str(STRAY_CLASS), []) if i not in migrated_ids]
        moved = [i for i in index.get(str(STRAY_CLASS), []) if i in migrated_ids]
        if stray:
            index[str(STRAY_CLASS)] = stray
        else:
            index.pop(str(STRAY_CLASS), None)
        if moved:
            index.setdefault(str(NAVAL_CLASS), []).extend(moved)
            index[str(NAVAL_CLASS)].sort()

    if migrated_ids:
        with io.open(path, "w", encoding="utf-8") as fh:
            json.dump(data, fh, ensure_ascii=False, indent=2)
            fh.write("\n")
    return len(migrated_ids)


def main():
    if not os.path.isdir(BASE):
        sys.exit(f"not found: {BASE} (run from the repository root)")
    total = 0
    for efile in sorted(os.listdir(BASE)):
        directory = os.path.join(BASE, efile)
        if not os.path.isdir(directory):
            continue
        count = sum(
            fix_file(os.path.join(directory, name))
            for name in sorted(os.listdir(directory))
            if name.endswith(".json")
        )
        if count:
            print(f"{efile}: {count} record(s) remapped")
        total += count
    print(f"total: {total}")


if __name__ == "__main__":
    main()
