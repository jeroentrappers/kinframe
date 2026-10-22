#!/usr/bin/env python3
"""Builds KinframeKit/Resources/Localizable.xcstrings from the web catalogs (web/src/lib/i18n/{en,nl}.ts) plus the
iOS-only strings in KinframeKit/Resources/ios-strings.json, so the apps share the web app's wording.

Keys, {placeholders} and .one/.other plural keys are kept exactly as on the web. Run after changing either source:
    ios/tools/strings.py
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WEB = ROOT / "web/src/lib/i18n"
RES = ROOT / "ios/KinframeKit/Resources"
LANGS = ["en", "nl"]

ENTRY = re.compile(r"""^\s*'([A-Za-z0-9_.]+)'\s*:\s*(?:\n\s*)?(['"])((?:\\.|(?!\2).)*)\2""", re.M)


def parse_ts(path: Path) -> dict:
    text = path.read_text(encoding="utf-8")
    out = {}
    for key, _quote, value in ENTRY.findall(text):
        out[key] = re.sub(r"\\(.)", r"\1", value)
    return out


def main() -> int:
    web = {lang: parse_ts(WEB / f"{lang}.ts") for lang in LANGS}
    ios = json.loads((RES / "ios-strings.json").read_text(encoding="utf-8"))
    strings = {}
    for key in sorted(set(web["en"]) | set(ios)):
        locs = {}
        for lang in LANGS:
            value = ios.get(key, {}).get(lang) if key in ios else web[lang].get(key)
            if value is None:
                print(f"missing {lang} for {key}", file=sys.stderr)
                return 1
            locs[lang] = {"stringUnit": {"state": "translated", "value": value}}
        strings[key] = {"extractionState": "manual", "localizations": locs}
    catalog = {"sourceLanguage": "en", "strings": strings, "version": "1.0"}
    (RES / "Localizable.xcstrings").write_text(
        json.dumps(catalog, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    print(f"{len(strings)} strings ({len(web['en'])} from web, {len(ios)} iOS-only)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
