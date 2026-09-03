#!/usr/bin/env python3
"""
Проверяет согласованность Kotlin-констант в Constants.kt с legacy JS.
Запускается как часть CI/автотестов.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CONSTANTS_KT = ROOT / "src" / "jsMain" / "kotlin" / "org" / "osada" / "Constants.kt"
LEGACY_JS = Path("C:/dev/hexagonal_wargames_source/Panzer_Marshal_3.2.14_Browser/panzermarshal.com/js/openpanzer.js")
INDEX_HTML = ROOT / "src" / "jsMain" / "resources" / "index.html"


def read_js_constants(text: str) -> dict:
    """Извлекает основные константы из var ...=...; блока в начале legacy JS."""
    consts = {}
    # VERSION
    m = re.search(r'VERSION\s*=\s*"([^"]+)"', text)
    if m:
        consts["VERSION"] = m.group(1)
    # UNIT_MAX_EXPERIENCE
    m = re.search(r'UNIT_MAX_EXPERIENCE\s*=\s*(\d+)', text)
    if m:
        consts["UNIT_MAX_EXPERIENCE"] = int(m.group(1))
    # movTableDry
    m = re.search(r'movTableDry\s*=\s*(\[\[.*?\]\])', text, re.DOTALL)
    if m:
        consts["movTableDry"] = m.group(1)
    m = re.search(r'movTableFrozen\s*=\s*(\[\[.*?\]\])', text, re.DOTALL)
    if m:
        consts["movTableFrozen"] = m.group(1)
    m = re.search(r'movTableMud\s*=\s*(\[\[.*?\]\])', text, re.DOTALL)
    if m:
        consts["movTableMud"] = m.group(1)
    return consts


def read_kt_constants(text: str) -> dict:
    consts = {}
    m = re.search(r'const\s+val\s+VERSION\s*=\s*"([^"]+)"', text)
    if m:
        consts["VERSION"] = m.group(1)
    m = re.search(r'const\s+val\s+UNIT_MAX_EXPERIENCE\s*=\s*(\d+)', text)
    if m:
        consts["UNIT_MAX_EXPERIENCE"] = int(m.group(1))
    # movement tables
    def extract_table(name):
        pattern = rf'val\s+{name}\s*:\s*List<List<Int>>\s*=\s*listOf\((.*?)\n\s*\)\s*\n'
        m = re.search(pattern, text, re.DOTALL)
        if not m:
            return None
        body = m.group(1)
        rows = re.findall(r'listOf\((.*?)\)', body, re.DOTALL)
        result = []
        for row in rows:
            nums = [int(x.strip()) for x in row.split(",") if x.strip()]
            result.append(nums)
        return result

    consts["movTableDry"] = extract_table("movTableDry")
    consts["movTableFrozen"] = extract_table("movTableFrozen")
    consts["movTableMud"] = extract_table("movTableMud")
    return consts


def parse_js_table(js_table: str):
    # Replace 255/254 etc, evaluate as Python literal (safe enough for known data)
    js_table = js_table.replace("\n", " ")
    try:
        return eval(js_table)
    except Exception as e:
        raise ValueError(f"Cannot parse JS table: {e}")


def main():
    errors = []
    warnings = []

    if not CONSTANTS_KT.exists():
        errors.append(f"Missing Kotlin constants file: {CONSTANTS_KT}")
    if not LEGACY_JS.exists():
        errors.append(f"Missing legacy JS file: {LEGACY_JS}")
    if not INDEX_HTML.exists():
        errors.append(f"Missing index.html: {INDEX_HTML}")

    if errors:
        print("=== FAIL: missing files ===")
        for e in errors:
            print(f"  {e}")
        sys.exit(1)

    js_text = LEGACY_JS.read_text(encoding="utf-8", errors="ignore")
    kt_text = CONSTANTS_KT.read_text(encoding="utf-8", errors="ignore")
    index_text = INDEX_HTML.read_text(encoding="utf-8", errors="ignore")

    # Check the served index.html loads the Kotlin/JS compiled output.
    if "osada.js" not in index_text:
        errors.append("index.html does not include osada.js")
    if "openGeneral.js" in index_text:
        warnings.append("index.html still references Kotlin-generated openGeneral.js but it is also expected to be loaded via gradle")

    # Check key symbols in legacy JS
    key_symbols = [
        ("GameState", r"\bGameState\s*=\s*function|function\s+GameState"),
        ("GameRules", r"\bGameRules\s*=\s*function|function\s+GameRules"),
        ("Unit", r"\bfunction\s+Unit\("),
        ("Player", r"\bfunction\s+Player\("),
        ("Hex", r"\bfunction\s+Hex\("),
        ("Map", r"\bfunction\s+Map\("),
        ("Scenario", r"\bfunction\s+Scenario\("),
        ("Campaign", r"\bfunction\s+Campaign\("),
        ("AI", r"\bfunction\s+AI\("),
        ("AIScripted", r"\bfunction\s+AIScripted\("),
        ("UI", r"\bfunction\s+UI\("),
        ("Render", r"\bfunction\s+Render\("),
    ]
    missing_symbols = [name for name, pattern in key_symbols if not re.search(pattern, js_text)]
    if missing_symbols:
        errors.append(f"legacy JS missing symbols: {missing_symbols}")

    js_consts = read_js_constants(js_text)
    kt_consts = read_kt_constants(kt_text)

    print("=== Kotlin/JS constants consistency ===")
    print(f"  JS VERSION:  {js_consts.get('VERSION')}")
    print(f"  KT VERSION:  {kt_consts.get('VERSION')}")
    print(f"  JS UNIT_MAX_EXPERIENCE:  {js_consts.get('UNIT_MAX_EXPERIENCE')}")
    print(f"  KT UNIT_MAX_EXPERIENCE:  {kt_consts.get('UNIT_MAX_EXPERIENCE')}")

    # OSADA is versioned independently of the legacy reference (see
    # ConstantsConsistencyTest.versionMatchesLegacy) -- only warn on drift, don't fail the build.
    if kt_consts.get("VERSION") != js_consts.get("VERSION"):
        warnings.append(
            f"VERSION differs from legacy reference (expected, OSADA versions independently): "
            f"Kotlin={kt_consts.get('VERSION')} vs legacy-2.3.14.js={js_consts.get('VERSION')}")
    if kt_consts.get("UNIT_MAX_EXPERIENCE") != js_consts.get("UNIT_MAX_EXPERIENCE"):
        errors.append(
            f"UNIT_MAX_EXPERIENCE mismatch: Kotlin={kt_consts.get('UNIT_MAX_EXPERIENCE')} vs legacy-2.3.14.js={js_consts.get('UNIT_MAX_EXPERIENCE')}")

    # OSADA added a 13th movement method (RAIL, for armored trains) with no legacy equivalent
    # (see the movTableDry comment in Constants.kt / ConstantsConsistencyTest); only the first
    # 12 rows, ported from the legacy JS, are compared here.
    for name in ["movTableDry", "movTableFrozen", "movTableMud"]:
        js_table = parse_js_table(js_consts.get(name, "[]"))
        kt_table = kt_consts.get(name)
        if kt_table is None:
            errors.append(f"Kotlin table {name} not found")
            continue
        kt_legacy_rows = kt_table[: len(js_table)]
        if js_table != kt_legacy_rows:
            errors.append(f"{name} mismatch between Kotlin and legacy-2.3.14.js")
        else:
            print(f"  {name}: OK ({len(kt_table)}x{len(kt_table[0])}, {len(js_table)} legacy rows verified)")

    if warnings:
        print("\n=== WARNINGS ===")
        for w in warnings:
            print(f"  {w}")

    if errors:
        print("\n=== ERRORS ===")
        for e in errors:
            print(f"  FAIL: {e}")
        sys.exit(1)

    print("\n=== OK: Kotlin/JS constants are consistent ===")


if __name__ == "__main__":
    main()
