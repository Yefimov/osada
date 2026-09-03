#!/usr/bin/env python3
"""Extract stable-key dialogue catalogs from OSADA's authored story campaigns.

The campaign JSON remains the English authoring source and compatibility fallback. Runtime
catalogs are committed resources so Kotlin/JS can load only the current scenario's dialogue.
"""

from __future__ import annotations

import argparse
import json
import xml.etree.ElementTree as ET
from collections import OrderedDict
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
CAMPAIGN_ROOT = ROOT / "src" / "jsMain" / "resources" / "resources" / "campaigns" / "data"
I18N_ROOT = ROOT / "src" / "jsMain" / "resources" / "i18n"
STORY_CAMPAIGNS = ("novemberrevolution.json", "rhu.json", "camp6bn4.json")
SCENARIO_ROOT = ROOT / "src" / "jsMain" / "resources" / "resources" / "scenarios" / "data"


def _text(value: Any) -> str:
    return value.strip() if isinstance(value, str) else ""


def extract_scenario(entry: dict[str, Any], include_operation_text: bool = False) -> OrderedDict[str, str]:
    """Return the translatable dialogue surface, keyed only by stable authored identities."""
    result: OrderedDict[str, str] = OrderedDict()
    def add(key: str, value: Any) -> None:
        text = _text(value)
        if text:
            if key in result:
                raise ValueError(f"duplicate extracted key {key}")
            result[key] = text

    if include_operation_text:
        scenario = _text(entry.get("scenario"))
        scenario_path = SCENARIO_ROOT / scenario
        if scenario_path.is_file():
            add("scenario.title", ET.parse(scenario_path).getroot().get("name"))
        add("intro.text", entry.get("intro"))

    briefing = entry.get("briefing")
    if not isinstance(briefing, dict):
        return result

    add("header.act", briefing.get("act"))
    add("header.location", briefing.get("location"))
    player = briefing.get("player")
    if isinstance(player, dict):
        add("player.speaker", player.get("speaker"))
        add("player.role", player.get("role"))

    for line in briefing.get("dialogue", []):
        if not isinstance(line, dict):
            continue
        line_id = _text(line.get("id"))
        if not line_id:
            raise ValueError(f"{entry.get('scenario')}: dialogue line has no stable id")
        prefix = f"line.{line_id}"
        add(f"{prefix}.speaker", line.get("speaker"))
        add(f"{prefix}.role", line.get("role"))
        add(f"{prefix}.text", line.get("text"))
        for choice in line.get("choices", []):
            if not isinstance(choice, dict):
                continue
            choice_id = _text(choice.get("id"))
            if not choice_id:
                raise ValueError(f"{entry.get('scenario')}/{line_id}: choice has no stable id")
            choice_prefix = f"{prefix}.choice.{choice_id}"
            add(f"{choice_prefix}.text", choice.get("text"))
            add(f"{choice_prefix}.hint", choice.get("hint"))

    for epilogue in entry.get("epilogues", []):
        if not isinstance(epilogue, dict):
            continue
        epilogue_id = _text(epilogue.get("id"))
        if not epilogue_id:
            raise ValueError(f"{entry.get('scenario')}: epilogue has no stable id")
        prefix = f"epilogue.{epilogue_id}"
        add(f"{prefix}.speaker", epilogue.get("speaker"))
        add(f"{prefix}.role", epilogue.get("role"))
        add(f"{prefix}.text", epilogue.get("text"))
    return result


def expected_catalogs() -> dict[Path, OrderedDict[str, str]]:
    catalogs: dict[Path, OrderedDict[str, str]] = {}
    for campaign_file in STORY_CAMPAIGNS:
        campaign_stem = Path(campaign_file).stem
        entries = json.loads((CAMPAIGN_ROOT / campaign_file).read_text(encoding="utf-8"))
        for entry in entries:
            scenario = _text(entry.get("scenario"))
            catalog = extract_scenario(entry, include_operation_text=campaign_file == "camp6bn4.json")
            if not scenario or not catalog:
                continue
            relative = Path("briefings") / campaign_stem / f"{Path(scenario).stem}.json"
            catalogs[relative] = catalog
    return catalogs


def write_english(catalogs: dict[Path, OrderedDict[str, str]]) -> None:
    for relative, catalog in catalogs.items():
        target = I18N_ROOT / "en" / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def check_english(catalogs: dict[Path, OrderedDict[str, str]]) -> list[str]:
    errors: list[str] = []
    for relative, expected in catalogs.items():
        path = I18N_ROOT / "en" / relative
        try:
            actual = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, UnicodeError, json.JSONDecodeError) as exc:
            errors.append(f"{path.relative_to(ROOT)}: {exc}")
            continue
        if actual != expected:
            errors.append(f"{path.relative_to(ROOT)}: differs from authored campaign dialogue; regenerate it")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--write-en", action="store_true", help="regenerate canonical English catalogs")
    args = parser.parse_args()
    catalogs = expected_catalogs()
    if args.write_en:
        write_english(catalogs)
        print(f"Wrote {len(catalogs)} English story-dialogue catalogs")
        return 0
    errors = check_english(catalogs)
    if errors:
        print("Story-dialogue catalog validation failed:")
        for error in errors:
            print(f"  - {error}")
        return 1
    print(f"Story-dialogue catalogs match {len(catalogs)} authored scenarios")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
