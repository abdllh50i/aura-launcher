"""Every Arabic string the screens show must have its English, or an English iPhone shows Arabic. Written as one list because
the Swift compiler cannot check it.

    python tools/check_strings.py           # says what is missing, exit 1 if anything is
    python tools/check_strings.py --list    # prints the missing keys as lines to paste into en.lproj/Localizable.strings
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCES = [ROOT / "App", ROOT / "Shared", ROOT / "ShareExt"]
STRINGS = ROOT / "Shared" / "Resources" / "en.lproj" / "Localizable.strings"

ARABIC = re.compile(r"[؀-ۿ]")
LITERAL = re.compile(r'"((?:[^"\\\n]|\\.)*)"')
# Text that is never translated: what the report says, what goes to the car, and test data.
SKIP_FILES = {"DemoScreens.swift", "DemoSearch.swift"}
SKIP_LINE = re.compile(r'verbatim: "|lines \+=|"الأنواع|"الرابط|"النص|"خطوة|note = "|static let word|Repair\.|^\s*//|^\s*///')
# In this file the Arabic is chosen in code, next to its English, except what goes through String(localized:).
CODE_CHOOSES = {"Formats.swift"}


def keys_in_code():
    keys = {}
    for root in SOURCES:
        for path in sorted(root.rglob("*.swift")):
            if path.name in SKIP_FILES:
                continue
            for number, line in enumerate(path.read_text(encoding="utf8").splitlines(), 1):
                if SKIP_LINE.search(line):
                    continue
                if path.name in CODE_CHOOSES and "String(localized:" not in line:
                    continue
                for match in LITERAL.finditer(line):
                    text = match.group(1)
                    if ARABIC.search(text):
                        keys.setdefault(text, f"{path.name}:{number}")
    return keys


def keys_in_strings():
    if not STRINGS.exists():
        return {}
    found = {}
    for key, value in re.findall(r'^"((?:[^"\\]|\\.)*)"\s*=\s*"((?:[^"\\]|\\.)*)";', STRINGS.read_text(encoding="utf8"), re.M):
        found[key] = value
    return found


def main():
    code = keys_in_code()
    english = keys_in_strings()
    missing = [key for key in code if key not in english]
    stale = [key for key in english if key not in code]
    if "--list" in sys.argv:
        for key in missing:
            print(f'"{key}" = "";')
        return 0
    for key in missing:
        print("no English for:", key, "(" + code[key] + ")")
    for key in stale:
        print("note: not used any more:", key)
    if any(not value for value in english.values()):
        print("empty English values:", [key for key, value in english.items() if not value])
        return 1
    print(f"{len(code)} strings, {len(missing)} missing")
    return 1 if missing else 0


if __name__ == "__main__":
    sys.exit(main())
