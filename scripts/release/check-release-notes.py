#!/usr/bin/env python3
"""Checks the committed store release notes before a release (docs/release.md).

- No emoji anywhere: App Store Connect rejects "What's New" with emoji, and Play shows them inconsistently.
- Play changelogs (android/fastlane/*metadata/android/<locale>/changelogs/*.txt) at most 500 characters.
- App Store release notes (ios/fastlane/metadata/<locale>/release_notes.txt) at most 4000 characters, not empty.

Run from the repository root: python3 -I scripts/release/check-release-notes.py
"""
import pathlib
import re
import sys

EMOJI = re.compile(
    "["
    "\U0001F000-\U0001FAFF"  # pictographs, emoticons, transport, symbols and pictographs extended, flags
    "\U00002600-\U000027BF"  # miscellaneous symbols, dingbats
    "\U00002B00-\U00002BFF"  # arrows and stars used as emoji
    "\U00002300-\U000023FF"  # watch, hourglass, media keys
    "\U0000FE0F\U0000200D\U000020E3"  # emoji presentation selector, zero-width joiner, keycap
    "]"
)

root = pathlib.Path(__file__).resolve().parents[2]
checks = [(p, 500) for p in sorted(root.glob("android/fastlane/*metadata/android/*/changelogs/*.txt"))]
checks += [(p, 4000) for p in sorted(root.glob("ios/fastlane/metadata/*/release_notes.txt"))]
if not checks:
    sys.exit("::error::no release notes found")

problems = []
for path, limit in checks:
    rel = path.relative_to(root)
    text = path.read_text(encoding="utf-8").strip()
    if not text:
        problems.append(f"{rel}: empty")
    if len(text) > limit:
        problems.append(f"{rel}: {len(text)} characters, the store allows {limit}")
    for n, line in enumerate(text.splitlines(), 1):
        for m in EMOJI.finditer(line):
            problems.append(f"{rel}:{n}: emoji U+{ord(m.group()):04X}")

for p in problems:
    print(f"::error::{p}")
if problems:
    sys.exit(1)
print(f"{len(checks)} release notes files: no emoji, within the store limits")
