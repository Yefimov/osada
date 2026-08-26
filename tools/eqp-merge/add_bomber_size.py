#!/usr/bin/env python3
"""
Additive patch: adds `bombsize` (OG's Bomber Size, `equip.xeqp` byte @22) to every `eqp-united`
record whose source (efile, ECode) can be re-identified -- WITHOUT touching eqids, groupings,
availability or any field the merge already wrote.

WHY THIS FIELD. OG gates its `Can bombard/barrage` ability on Bomber Size being greater than zero
(manual §9.2, and the game's own `tips1.txt`: *"Artillery or Capital ship with '=' at top of unit
info screen are able to Barrage attack"*). It is **not** one of the 52 special bits -- the owner's
2026-08-26 exports proved that, and the population proves the rule: every LXF Level Bomber (101/101)
and every Battleship (79/79) carries it. See `docs/og-fidelity-plan.md` §Q.2 and §R.

WHY A PATCHER, NOT A MERGE RE-RUN. Exactly `add_special4_specialex.py`'s reason: `resolve_groups`
compares every non-excluded field when it groups variants, so adding a field to the pre-merge input
could split a group and change eqids that 502 shipped scenarios and every save file already carry.
This works backwards from what shipped instead, reusing `verify_specials.py`'s own identification
(the eqid map, an exact match on the fields the merge copies, then its tie-break).

Records whose source cannot be identified, and Panzer Marshal's own stock rosters which have no OG
source at all, are left without the field -- absent means "no data", exactly as it does for
`attr2`/`attrEx`, and `EquipmentData.bombsize` parses that as 0.

Usage:  python tools/eqp-merge/add_bomber_size.py [--write] [--verbose]
        (dry-run by default)
"""
import argparse
import json
import sys
from collections import Counter, defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from verify_specials import (  # noqa: E402
    EFILE_PRIORITY,
    HDR,
    MERGE_OUT_DIR,
    OG,
    PM_STOCK,
    REC,
    SOURCES,
    UNITED_DIR,
    identify,
    load_equipment,
)

# OG `equip.xeqp` field "BombCode" -- Bomber Size. Same offset `xeqp_to_csv.py` reads it from, and
# the only byte in the record that matches OG's `CanBarrage` display exactly.
BOMB_SIZE_OFFSET = 22
FIELD = "bombsize"


def bomber_size(blob: bytes, index: int) -> int:
    return blob[HDR + index * REC + BOMB_SIZE_OFFSET]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--write", action="store_true", help="apply (default: dry-run)")
    parser.add_argument("--verbose", action="store_true", help="list every record that gains a value")
    args = parser.parse_args()

    blobs = {}
    for tag, (relative, _) in SOURCES.items():
        path = OG / relative
        if not path.exists():
            sys.exit(f"missing OG source binary for {tag}: {path}")
        blob = path.read_bytes()
        if (len(blob) - HDR) % REC:
            sys.exit(f"{path} is not a 4 + N*122 equipment binary ({len(blob)} bytes)")
        blobs[tag] = blob

    committed = {tag: load_equipment(tag) for tag in SOURCES}
    eqid_map = json.loads((MERGE_OUT_DIR / "eqid-map.json").read_text(encoding="utf-8"))
    for tag in eqid_map:
        if tag not in SOURCES and tag not in PM_STOCK:
            print(f"WARNING: eqid-map.json names {tag!r}, which this script does not classify", file=sys.stderr)

    candidates = defaultdict(list)
    for tag in SOURCES:
        for old, new in eqid_map.get(tag, {}).items():
            if old in committed[tag]:
                candidates[new].append((tag, old))
    rank = {tag: i for i, tag in enumerate(EFILE_PRIORITY)}

    paths = sorted(UNITED_DIR.glob("equipment-country-*.json"))
    parsed = {path: json.loads(path.read_text(encoding="utf-8")) for path in paths}

    stats = Counter()
    dirty = set()
    for path, data in parsed.items():
        hints = data["parsehints"]
        if FIELD not in hints:
            hints.append(FIELD)
            dirty.add(path)
        column = hints.index(FIELD)
        for eqid, row in data["units"].items():
            while len(row) <= column:
                row.append(0)
            shipped = dict(zip(hints, row))
            found = identify(shipped, candidates.get(int(eqid), []), committed, rank)
            if found is None:
                stats["unidentified"] += 1
                continue
            tag, old, _exact = found
            size = bomber_size(blobs[tag], int(old))
            stats["identified"] += 1
            if size:
                stats["can_barrage"] += 1
            if row[column] != size:
                row[column] = size
                dirty.add(path)
                if args.verbose and size:
                    print(f"  {eqid:>6} {shipped.get('name', '')[:28]:30} bombsize={size} ({tag} {old})")

    print(f"identified {stats['identified']}, unidentified {stats['unidentified']}, "
          f"with Bomber Size > 0: {stats['can_barrage']}")
    print(f"files to write: {len(dirty)}")
    if args.write:
        for path in sorted(dirty):
            # The shipped country files are pretty-printed with indent=2, unescaped UTF-8 and a
            # trailing newline; reproducing that exactly keeps the diff to the rows that changed.
            body = json.dumps(parsed[path], ensure_ascii=False, indent=2)
            path.write_text(body + "\n", encoding="utf-8")
        print("written")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
