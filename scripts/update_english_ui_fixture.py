#!/usr/bin/env python3
"""Regenerate the synchronous English UI bundle used by Kotlin/JS DOM tests."""

from __future__ import annotations

import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "src" / "jsMain" / "resources" / "i18n" / "en" / "ui.json"
TARGET = (
    ROOT
    / "src"
    / "jsTest"
    / "kotlin"
    / "org"
    / "osada"
    / "i18n"
    / "EnglishUiBundleFixture.kt"
)

HEADER = """package org.osada.i18n

// Generated from src/jsMain/resources/i18n/en/ui.json by scripts/update_english_ui_fixture.py.
// Production loads this bundle asynchronously over XHR, which Karma's test context cannot serve
// reliably; DOM tests install this synchronous copy with installEnglishUiBundleForTests().
@Suppress("ktlint:standard:max-line-length")
internal const val EN_UI_BUNDLE_JSON =
"""

FOOTER = """

internal fun installEnglishUiBundleForTests() {
    // installBundlesForTests always writes the selected bundle over the English one when
    // selectedLanguage is ENGLISH. Use an empty Russian overlay so keys fall through to English.
    I18n.installBundlesForTests(
        english = EN_UI_BUNDLE_JSON,
        selected = "{}",
        selectedLanguage = Language.RUSSIAN,
    )
}
"""


def main() -> None:
    bundle = json.loads(SOURCE.read_text(encoding="utf-8"))
    compact = json.dumps(bundle, ensure_ascii=True, separators=(",", ":"))
    TARGET.write_text(f'{HEADER}    """{compact}"""{FOOTER}', encoding="utf-8")
    print(f"Updated {TARGET.relative_to(ROOT)} with {len(bundle)} keys")


if __name__ == "__main__":
    main()
