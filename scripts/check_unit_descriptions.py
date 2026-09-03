#!/usr/bin/env python3
"""Validate row-specific narrative descriptions and the generated runtime resource."""

from __future__ import annotations

import json
from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parent.parent
DESCRIPTION_TOOLS = ROOT / "tools" / "descriptions"
sys.path.insert(0, str(DESCRIPTION_TOOLS))

from narrative_descriptions import (  # noqa: E402
    TARGET_FILES,
    audit_narrative_descriptions,
    build_narrative_descriptions,
    load_target_rows,
)


EQUIPMENT_DIR = ROOT / "src" / "jsMain" / "resources" / "resources" / "equipment" / "eqp-united"
OUTPUT = EQUIPMENT_DIR / "unit-descriptions.json"


def require_fragment(rows, descriptions, country_file, name, fragment):
    matches = [row for row in rows if row.country_file == country_file and row.name == name]
    if not matches:
        return [f"fixture unit missing: country-file {country_file}, {name}"]
    failures = []
    for row in matches:
        text = descriptions[str(row.eqid)].casefold()
        if fragment.casefold() not in text:
            failures.append(f"{row.country} eqid={row.eqid} {name}: expected '{fragment}'")
    return failures


def main() -> int:
    payload = json.loads(OUTPUT.read_text(encoding="utf-8"))
    if payload.get("version") != 2 or not isinstance(payload.get("byName"), dict) or not isinstance(payload.get("byId"), dict):
        print("unit description check: runtime JSON is not version 2")
        return 1

    generated = build_narrative_descriptions(EQUIPMENT_DIR)
    rows = load_target_rows(EQUIPMENT_DIR)
    errors = audit_narrative_descriptions(EQUIPMENT_DIR, generated)
    if payload["byId"] != generated:
        errors.append("unit-descriptions.json is stale; run python tools/descriptions/build_descriptions.py")

    checks = (
        (20, "BM-13-16", "132-mm rockets"),
        # T-34/43's predecessor inside the shared `t34` family is the 1942 pattern, not the 1941
        # one: `family_key` strips the `/76` sub-designation, so "T-34/76 1942" sorts between them.
        # Updated 2026-08-17 after the generated file was rebuilt against the current eqp-united
        # numbering; the assertion still guards that a later variant is compared to its immediate
        # predecessor rather than to nothing.
        (62, "T-34/43", "preceding 1942 pattern"),
        (62, "Novobranets 43", "preceding 1941 pattern"),
        (0, "Airport", "land, rearm"),
        (0, "Supply Dump", "distribution"),
        (8, "Bf-109B", "MG 17 machine guns"),
        (8, "Brückenlegepanzer IV", "bridging equipment"),
        (6, "Type 97 Chi-Ha", "57-mm"),
        (6, "A6M2", "20-mm cannon"),
        (104, "Tachanka", "Maxim machine gun"),
    )
    for country_file, name, fragment in checks:
        errors.extend(require_fragment(rows, generated, country_file, name, fragment))

    nazi_praise = ("glorious", "master race", "finest", "unstoppable", "legendary", "superior design", "heroic nazi")
    for row in rows:
        text = generated[str(row.eqid)].casefold()
        if row.country == "Germany" and 1933 <= row.year <= 1945:
            if "nazi" not in text:
                errors.append(f"Germany eqid={row.eqid} {row.name}: Nazi-era context is missing")
            for phrase in nazi_praise:
                if phrase in text:
                    errors.append(f"Germany eqid={row.eqid} {row.name}: complimentary phrase '{phrase}'")

    if errors:
        print(f"unit description check: {len(errors)} error(s)")
        for error in errors[:100]:
            print(f"  {error}")
        if len(errors) > 100:
            print(f"  ... and {len(errors) - 100} more")
        return 1

    counts = {country_file: 0 for country_file in TARGET_FILES}
    for row in rows:
        counts[row.country_file] += 1
    print(f"unit description check: OK ({len(rows)} exact equipment rows)")
    for country_file, country in TARGET_FILES.items():
        print(f"  {country:12} file {country_file:3}: {counts[country_file]:4} descriptions")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
