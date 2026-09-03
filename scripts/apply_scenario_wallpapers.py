"""
Wire the generated scenario wallpapers into the game.

Each campaign is covered by a small set of "chapter" wallpapers rather than one image
per scenario: a campaign's operations are grouped by theatre/season/phase, and every
scenario in a group opens on the same key art. The grouping below follows the art
briefs the images were generated from.

Two consumers are fed from the single ASSIGNMENTS table:

  * the scenario briefing backdrop -- written as `briefing.background` into each
    campaign JSON in resources/campaigns/data/, which is the key
    ScenarioBriefingController already reads (falling back to the staff-table default
    when it is absent);
  * the Scenario Selection dossier banner -- emitted as resources/campaigns/wallpapers.js,
    a generated `scenarioWallpapers` global keyed by scenario XML file name, in the same
    style as scenariolist.js / campaignlist.js.

Art pipeline: masters live untracked in art-src/wallpapers/ (PNG, as generated); the
served copies are JPEG q88, matching the campaign theater banners' reasoning in
StartMenuCampaignData.setTheaterArt -- a 2 MB lossless backdrop is a visible stall on
every scenario launch, and at q88 the two are indistinguishable on screen.

Usage:
    python scripts/apply_scenario_wallpapers.py --import <dir-of-generated-pngs>
    python scripts/apply_scenario_wallpapers.py            # encode + wire (idempotent)
"""

from __future__ import annotations

import argparse
import json
import shutil
import sys
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parent.parent
ART_SRC = ROOT / "art-src" / "wallpapers"
RESOURCES = ROOT / "src" / "jsMain" / "resources" / "resources"
ART_OUT = RESOURCES / "ui" / "wallpapers"
CAMPAIGN_DATA = RESOURCES / "campaigns" / "data"
WALLPAPER_JS = RESOURCES / "campaigns" / "wallpapers.js"

# Web path the game requests, relative to index.html.
WEB_PREFIX = "resources/ui/wallpapers"

JPEG_QUALITY = 88

# slug -> file name of the generated PNG it was imported from.
SOURCES: dict[str, str] = {
    "gpw-1": "GPW-1 — Stalingrad encirclement and winter counteroffensive.png",
    "gpw-2": "GPW-2 — Kursk, Orel and the liberation of Ukraine.png",
    "gpw-3": "GPW-3 — Operation Bagration and the road through Belarus and Poland.png",
    "gpw-4": "GPW-4 — Hungary, East Prussia and Berlin.png",
    "lf-1": "LF-1 — German democratic revolution, 1848–1849.png",
    "lf-2": "LF-2 — Missouri and the divided American frontier.png",
    "lf-3": "LF-3 — The great eastern battlefields.png",
    "lf-4": "LF-4 — Siege lines, coasts and Richmond.png",
    "part-2": "Sabotage war, 1942–1943.png",
    "part-3": "Partisans become an army, 1944–1945.png",
    "yug-1": "YUG-1 — Serbian uprising and first repression.png",
    "yug-2": "YUG-2 — Mountain retreat and guerrilla railway war.png",
    "yug-3": "YUG-3 — Neretva, Sutjeska and the encirclement battles.png",
    "yug-4": "YUG-4 — Liberation from Belgrade to Trieste.png",
    "rac-1": "RAC-1 — International beginnings.png",
    "rac-2": "RAC-2 — Poland, Finland and the catastrophe of 1941.png",
    "rac-3": "RAC-3 — Kharkov, Voronezh and Kursk.png",
    "rac-4": "RAC-4 — Liberation and the Far East.png",
    "bcav-1": "BCAV-1 — Don cavalry war.png",
    "bcav-2": "BCAV-2 — The Polish campaign.png",
    "bcav-3": "BCAV-3 — Crimea and the end of the Civil War.png",
    "fc-1": "FC-1 — Border wars and the northern winter.png",
    "fc-2": "FC-2 — 1941–1942 survival.png",
    "fc-3": "FC-3 — Strategic recovery, 1942–1944.png",
    "fc-4": "FC-4 — Germany, Western Europe and the alternate endgame.png",
    "zh-1": "ZH-1 — Khalkhin Gol and the prewar command.png",
    "zh-2": "ZH-2 — Moscow and Rzhev.png",
    "zh-3": "ZH-3 — Bagration and the Vistula.png",
    "zh-4": "ZH-4 Oder and Berlin.png",
    "ep-1": "EP-1 — Madrid and the central front.png",
    "ep-2": "EP-2 — Aragón and Teruel.png",
    "ep-3": "EP-3 — Ebro and the last alternatives.png",
    # NK-3 was never generated under that name; "North Korea 3" is the image that matches
    # its brief (spring ridgelines, trenches, artillery smoke). "North Korea 1" and
    # "North Korea 2" are unused alternates and are not imported.
    "nk-1": "NK-1 — The summer advance, 1950.png",
    "nk-2": "NK-2 — Collapse, intervention and winter war.png",
    "nk-3": "North Korea 3.png",
    "vc-1": "VC-1 — Coastal and village guerrilla war.png",
    "vc-2": "VC-2 — Central Highlands.png",
    "vc-3": "VC-3 — Khe Sanh and Tet.png",
    "vc-4": "VC-4 — Laos, Kontum and Saigon.png",
    "psw-1": "PSW-1 — Lithuania and Belarus.png",
    "psw-2": "PSW-2 — Kiev to Warsaw.png",
    "psw-3": "PSW-3 — Komarów, Niemen and retreat.png",
    "bsf-1": "BSF-1 — Danube, Bessarabia and Odessa, 1941.png",
    "bsf-2": "BSF-2 — Crimea and Sevastopol, 1941–1942.png",
    "bsf-3": "BSF-3 — Caucasus and Kuban, 1942–1943.png",
    "bsf-4": "BSF-4 — Return to Crimea and the Balkans, 1944.png",
    "rd-1": "RD-1 — Berlin aftermath and the Far Eastern transfer.png",
    "rd-2": "RD-2 — Mediterranean offensive.png",
    "rd-3": "RD-3 — Low Countries, Ardennes and Dunkirk.png",
    "rd-4": "RD-4 — Britain, Iberia and internal revolt.png",
    "rsc-1": "RSC-1 — Revolutionary bases and failed uprisings.png",
    "rsc-2": "RSC-2 — The Long March.png",
    "rsc-3": "RSC-3 — War against Japan and civil-war resurgence.png",
    "rsc-4": "RSC-4 — Korea and speculative post-1949 branches.png",
    "novrev-1": "novrev_naval_uprising_1918.png",
    "novrev-2": "novrev_revolution_inland_1918.png",
    "novrev-3": "novrev_berlin_fall_of_monarchy_1918.png",
    "rhu-1": "rhu_eastern_retreat_april_1919.png",
    "rhu-2": "rhu_defensive_line_spring_1919.png",
    "rhu-3": "rhu_northern_campaign_1919.png",
    "rhu-4": "rhu_tisza_endgame_july_1919.png",
}

# campaign JSON -> slug -> scenario ids (the campaign entry's own "id", which is also its
# index in the campaign's scenario array). Campaigns absent here have no art yet and keep
# the shared staff-table default: acampdf2 (Czech Legion), camp6 (Soviet Counter-Offensive),
# simpob (Sim Pobedishi), spa (Spartacus), volarm (Defeat of Denikin) -- plus the first
# three Red Partisans operations, whose PART-1 forest image was never generated.
ASSIGNMENTS: dict[str, dict[str, list[int]]] = {
    # The Great Patriotic War USSR Campaign. Uranus/Saturn/Magyar Massacre are the winter
    # counteroffensive; Operation Star opens the Ukrainian chapter per the art brief.
    "062d.json": {
        "gpw-1": [0, 1, 2],
        "gpw-2": [3, 4, 5, 6, 7, 8, 9],
        "gpw-3": [10, 11, 12, 13],
        "gpw-4": [14, 15, 16, 17, 18, 19],
    },
    # A Long Journey to Freedom. The eastern-battlefield and siege groups interleave by
    # date, so these are explicit id lists rather than ranges.
    "aljf.json": {
        "lf-1": [0, 1, 2, 3],
        "lf-2": [4, 5, 6, 7, 8],
        "lf-3": [9, 10, 11, 12, 13, 15, 16, 18, 19],
        "lf-4": [14, 17, 20, 21, 22, 23, 24, 25],
    },
    # Stay Alive - Red Partisans. 0-2 (Guerrilla Hunter, German Blitz, Sink the Tirpitz)
    # await PART-1.
    "camp6bn5.json": {
        "part-2": [3, 4, 5, 6],
        "part-3": [7, 8, 9],
    },
    "camp6bn8.json": {
        "yug-1": [0, 1, 2],
        "yug-2": [3, 4, 5, 6],
        "yug-3": [7, 8, 9, 10, 11],
        "yug-4": [12, 13, 14, 15, 16, 17, 18],
    },
    "camp6bn9.json": {
        "rac-1": [0, 1, 2],
        "rac-2": [3, 4, 5, 6, 7, 8],
        "rac-3": [9, 10, 11, 12, 13],
        "rac-4": [14, 15, 16, 17, 18, 19, 20],
    },
    "ccampdfc.json": {
        "bcav-1": [0, 1],
        "bcav-2": [2, 3, 4, 5, 6, 7, 8, 9],
        "bcav-3": [10, 11, 12],
    },
    # Forward, Comrade!. The campaign file is not in date order: 23-24 and 38-41 are
    # 1943-44 eastern operations that belong with FC-3, 33-37 are the alternate western
    # branch duplicates of 28-32, and 22 (Manchuria) reads as FC-1's Far Eastern steppe.
    "forward.json": {
        "fc-1": [0, 1, 22],
        "fc-2": [2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12],
        "fc-3": [13, 14, 15, 16, 17, 18, 19, 20, 21, 23, 24, 38, 39, 40, 41, 42],
        "fc-4": [25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 43],
    },
    # Zhukov. Kutuzov (6) opens the summer-offensive chapter alongside Bagration rather
    # than closing the snowbound central-front one.
    "ga4.json": {
        "zh-1": [0, 1],
        "zh-2": [2, 3, 4, 5],
        "zh-3": [6, 7, 8, 9, 10, 11, 12, 13, 14],
        "zh-4": [15, 16],
    },
    "gce.json": {
        "ep-1": [0, 1, 2, 3],
        "ep-2": [4, 5],
        "ep-3": [6, 7, 8, 9, 10, 11],
    },
    # North Korea. Majon-ni (13) is filed last in the campaign array but belongs with the
    # November-December 1950 winter retreat.
    "ncampdfn.json": {
        "nk-1": [0, 1, 2, 3, 4],
        "nk-2": [5, 6, 7, 8, 13],
        "nk-3": [9, 10, 11, 12],
    },
    "nvc.json": {
        "vc-1": [0, 1],
        "vc-2": [2, 3, 4, 5, 6],
        "vc-3": [7, 8],
        "vc-4": [9, 10, 11],
    },
    "polsov.json": {
        "psw-1": [0, 1, 2, 3],
        "psw-2": [4, 5, 6, 7],
        "psw-3": [8, 9, 10],
    },
    # Soviet Black Sea Fleet. Rostov 1941 (5) goes with the Caucasus chapter the art brief
    # files it under, not with the 1941 Danube/Odessa opening.
    "rcampdfr.json": {
        "bsf-1": [0, 1, 2, 3, 4],
        "bsf-2": [6, 7, 8, 9],
        "bsf-3": [5, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19],
        "bsf-4": [20, 21, 22, 23, 24, 25, 26],
    },
    "reddestiny.json": {
        "rd-1": [0, 1, 2, 3, 4],
        "rd-2": [5, 6, 7, 8],
        "rd-3": [9, 10, 11],
        "rd-4": [12, 13, 14, 15, 16, 17, 18],
    },
    "rsoc.json": {
        "rsc-1": [0, 1, 2],
        "rsc-2": [3],
        "rsc-3": [4, 5, 6, 7, 8, 9],
        "rsc-4": [10, 11, 12, 13, 14, 15, 16, 17, 18],
    },
    "novemberrevolution.json": {
        "novrev-1": [0, 1],
        "novrev-2": [2],
        "novrev-3": [3],
    },
    "rhu.json": {
        "rhu-1": [0, 1],
        "rhu-2": [2, 3],
        "rhu-3": [4, 5, 6, 7],
        "rhu-4": [8, 9],
    },
}


def web_path(slug: str) -> str:
    return f"{WEB_PREFIX}/{slug}.jpg"


def detect_indent(text: str) -> int:
    """Campaign JSON files are checked in at 1- or 2-space indent; keep each file's own."""
    for line in text.splitlines()[1:]:
        stripped = line.lstrip(" ")
        if stripped:
            return len(line) - len(stripped)
    return 1


def import_masters(source: Path) -> int:
    """Copy the generated PNGs into art-src/ under their slug names."""
    ART_SRC.mkdir(parents=True, exist_ok=True)
    missing = [name for name in SOURCES.values() if not (source / name).is_file()]
    if missing:
        print(f"Missing {len(missing)} source image(s) in {source}:", file=sys.stderr)
        for name in missing:
            print(f"  {name}", file=sys.stderr)
        return 1
    for slug, name in SOURCES.items():
        shutil.copyfile(source / name, ART_SRC / f"{slug}.png")
    print(f"Imported {len(SOURCES)} masters into {ART_SRC.relative_to(ROOT)}")
    return 0


def encode_art() -> int:
    """Re-encode every master as a served JPEG. Skips masters that are already current."""
    from PIL import Image

    ART_OUT.mkdir(parents=True, exist_ok=True)
    written = 0
    for slug in sorted(SOURCES):
        master = ART_SRC / f"{slug}.png"
        if not master.is_file():
            print(f"[skip] no master for {slug}", file=sys.stderr)
            continue
        target = ART_OUT / f"{slug}.jpg"
        if target.is_file() and target.stat().st_mtime >= master.stat().st_mtime:
            continue
        with Image.open(master) as image:
            image.convert("RGB").save(
                target,
                "JPEG",
                quality=JPEG_QUALITY,
                optimize=True,
                progressive=True,
            )
        written += 1
    print(f"Encoded {written} wallpaper(s) into {ART_OUT.relative_to(ROOT)}")
    return 0


def stamp_campaign_json() -> tuple[int, dict[str, str]]:
    """Write briefing.background into every assigned scenario. Returns (changed, xml->url)."""
    by_scenario: dict[str, str] = {}
    changed = 0
    for campaign_file, groups in ASSIGNMENTS.items():
        path = CAMPAIGN_DATA / campaign_file
        text = path.read_text(encoding="utf-8")
        indent = detect_indent(text)
        entries: list[dict[str, Any]] = json.loads(text)

        wanted: dict[int, str] = {}
        for slug, ids in groups.items():
            for scenario_id in ids:
                wanted[scenario_id] = web_path(slug)

        unknown = sorted(set(wanted) - {index for index, _ in enumerate(entries)})
        if unknown:
            raise SystemExit(f"{campaign_file}: assigned ids out of range: {unknown}")

        for index, entry in enumerate(entries):
            url = wanted.get(index)
            if url is None:
                continue
            briefing = entry.get("briefing")
            if not isinstance(briefing, dict):
                briefing = {}
                entry["briefing"] = briefing
            briefing["background"] = url
            scenario_xml = entry.get("scenario")
            if isinstance(scenario_xml, str) and scenario_xml:
                by_scenario[scenario_xml] = url

        updated = json.dumps(entries, ensure_ascii=False, indent=indent) + "\n"
        if updated != text:
            path.write_text(updated, encoding="utf-8", newline="\n")
            changed += 1
    print(f"Stamped briefing.background in {changed} campaign file(s)")
    return changed, by_scenario


def write_wallpaper_js(by_scenario: dict[str, str]) -> None:
    lines = [
        "//Automatically generated by scripts/apply_scenario_wallpapers.py",
        "//Scenario XML file -> the chapter wallpaper its operation opens on.",
        "var scenarioWallpapers =",
        "{",
    ]
    items = sorted(by_scenario.items())
    for position, (scenario_xml, url) in enumerate(items):
        comma = "," if position < len(items) - 1 else ""
        lines.append(f' "{scenario_xml}": "{url}"{comma}')
    lines.append("};")
    WALLPAPER_JS.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    print(f"Wrote {len(items)} entries to {WALLPAPER_JS.relative_to(ROOT)}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--import",
        dest="import_dir",
        type=Path,
        help="Copy the generated PNGs from this directory into art-src/wallpapers/ first.",
    )
    parser.add_argument(
        "--skip-art",
        action="store_true",
        help="Only rewrite the campaign JSON and wallpapers.js; leave the JPEGs alone.",
    )
    args = parser.parse_args()

    if args.import_dir is not None:
        code = import_masters(args.import_dir)
        if code:
            return code

    if not args.skip_art:
        code = encode_art()
        if code:
            return code

    _, by_scenario = stamp_campaign_json()
    write_wallpaper_js(by_scenario)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
