#!/usr/bin/env python3
"""Сравнение legacy JS-файлов openpanzer."""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RESOURCES = ROOT / "src" / "jsMain" / "resources" / "openpanzer" / "js"

FILES = {
    "legacy-2.3.14": RESOURCES / "openpanzer-legacy-2.3.14.js",
    "legacy": RESOURCES / "openpanzer-legacy.js",
}


def extract_globals(path: Path):
    text = path.read_text(encoding="utf-8", errors="ignore")
    funcs = set(re.findall(r"function\s+([A-Za-z_$][A-Za-z0-9_$]*)\s*\(", text))
    vars_ = set(re.findall(r"\bvar\s+([A-Za-z_$][A-Za-z0-9_$]*)\s*=", text))
    consts = {}
    for m in re.finditer(r"\bvar\s+([A-Z_][A-Z0-9_]*)\s*=\s*([^;]+);", text):
        consts[m.group(1)] = m.group(2).strip()
    classes = set(re.findall(r"function\s+([A-Z][A-Za-z0-9_$]*)\s*\(", text))
    return funcs, vars_, consts, classes, len(text)


def main():
    data = {}
    for name, path in FILES.items():
        funcs, vars_, consts, classes, size = extract_globals(path)
        data[name] = {"funcs": funcs, "vars": vars_, "consts": consts, "classes": classes, "size": size}
        print(f"=== {name} ({path.name}) ===")
        print(f"  size: {size} bytes")
        print(f"  functions: {len(funcs)}, vars: {len(vars_)}, classes: {len(classes)}, consts: {len(consts)}")
        print(f"  sample consts: {dict(list(consts.items())[:10])}")
        print()

    print("=== DIFF of top-level constants ===")
    keys = set(data["legacy-2.3.14"]["consts"]) | set(data["legacy"]["consts"])
    diffs = 0
    for k in sorted(keys):
        v1 = data["legacy-2.3.14"]["consts"].get(k)
        v2 = data["legacy"]["consts"].get(k)
        if v1 != v2:
            print(f"  {k}: 2.3.14={v1}  legacy={v2}")
            diffs += 1
    print(f"  total differing constants: {diffs}")
    print()

    print("=== Top-level symbols unique to legacy-2.3.14 ===")
    only_new = data["legacy-2.3.14"]["funcs"] - data["legacy"]["funcs"]
    print(f"  new functions: {len(only_new)}")
    for s in sorted(only_new)[:20]:
        print(f"    {s}")
    print()

    print("=== Top-level symbols only in legacy (removed from 2.3.14) ===")
    only_old = data["legacy"]["funcs"] - data["legacy-2.3.14"]["funcs"]
    print(f"  removed functions: {len(only_old)}")
    for s in sorted(only_old)[:20]:
        print(f"    {s}")
    print()

    # Check key game classes present
    key_classes = {"GameState", "GameRules", "Unit", "Player", "Hex", "Map", "Scenario", "Campaign", "AI", "AIScripted", "UI", "Render", "UIBuilder"}
    print("=== Key game classes/functions present ===")
    for name in FILES:
        present = key_classes & data[name]["classes"]
        missing = key_classes - data[name]["classes"]
        print(f"  {name}: present {len(present)}/{len(key_classes)} missing={sorted(missing)}")


if __name__ == "__main__":
    main()
