"""Build the offline emoji asset from pinned Unicode 16.0 / CLDR 46 data.

Run from any directory: python scripts/build-emoji-catalog.py
The source files and license are retained in third_party/unicode-emoji.
"""
from pathlib import Path
import re
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "third_party/unicode-emoji"
SOURCES = {
    "emoji-test.txt": "https://www.unicode.org/Public/emoji/16.0/emoji-test.txt",
    "annotations-en.xml": "https://raw.githubusercontent.com/unicode-org/cldr/release-46/common/annotations/en.xml",
    "annotations-derived-en.xml": "https://raw.githubusercontent.com/unicode-org/cldr/release-46/common/annotationsDerived/en.xml",
    "LICENSE.txt": "https://raw.githubusercontent.com/unicode-org/cldr/release-46/LICENSE",
}
# Small first-party aliases for common conversational searches missing from CLDR.
ALIASES = {
    "😀": "happy", "😃": "happy", "😄": "happy", "😁": "happy", "😊": "happy",
    "🙂": "happy", "🥰": "happy love", "😂": "lol laughing", "🤣": "lol laughing",
    "😔": "sad", "😞": "sad", "😢": "sad", "😭": "sad", "🙁": "sad", "☹️": "sad",
    "🙏": "thanks thank you please", "👍": "yes like", "👎": "no dislike",
    "🎉": "party congratulations", "🎊": "party congratulations", "🥳": "party",
    "🇺🇸": "usa us america", "🇬🇧": "uk britain", "😎": "cool",
}


def canonical(text):
    return text.replace("\ufe0f", "")


def build():
    SOURCE.mkdir(parents=True, exist_ok=True)
    for name, url in SOURCES.items():
        path = SOURCE / name
        if not path.exists():
            with urllib.request.urlopen(url, timeout=60) as response:
                path.write_bytes(response.read())

    keywords = {}
    for name in ("annotations-en.xml", "annotations-derived-en.xml"):
        for item in ET.parse(SOURCE / name).iter("annotation"):
            key = canonical(item.attrib["cp"])
            keywords.setdefault(key, []).append(item.text or "")

    rows = ["# Unicode Emoji 16.0; CLDR 46 English annotations; Unicode License v3",
            "# emoji\tcategory\tname\tkeywords\tbase emoji"]
    group = ""
    for line in (SOURCE / "emoji-test.txt").read_text(encoding="utf-8").splitlines():
        if line.startswith("# group: "):
            group = line.removeprefix("# group: ")
        match = re.match(r"^([0-9A-F ]+)\s*; fully-qualified\s*# \S+ E[\d.]+ (.+)$", line)
        if not match or group == "Component":
            continue
        emoji = "".join(chr(int(cp, 16)) for cp in match[1].split())
        base = canonical("".join(c for c in emoji if not 0x1F3FB <= ord(c) <= 0x1F3FF))
        words = " | ".join(keywords.get(canonical(emoji), []))
        if emoji in ALIASES:
            words += " | " + ALIASES[emoji]
        rows.append("\t".join((emoji, group, match[2], words, base)))
    target = ROOT / "app/src/main/assets/emoji/catalog.tsv"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text("\n".join(rows) + "\n", encoding="utf-8", newline="\n")
    license_target = ROOT / "app/src/main/assets/licenses/Unicode.txt"
    license_target.parent.mkdir(parents=True, exist_ok=True)
    license_target.write_bytes((SOURCE / "LICENSE.txt").read_bytes())
    print(f"Generated {len(rows) - 2} emoji entries ({target.stat().st_size:,} bytes)")


if __name__ == "__main__":
    build()
