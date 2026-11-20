# Unicode emoji data

The emoji picker bundles Unicode Emoji 16.0 fully-qualified sequences and CLDR
46 English names/keywords under the Unicode License v3. The unmodified source
files and license are retained in this directory; a copy of the license ships
in the APK at `assets/licenses/Unicode.txt`.

Sources:

- https://www.unicode.org/Public/emoji/16.0/emoji-test.txt
- https://github.com/unicode-org/cldr/blob/release-46/common/annotations/en.xml
- https://github.com/unicode-org/cldr/blob/release-46/common/annotationsDerived/en.xml
- https://github.com/unicode-org/cldr/blob/release-46/LICENSE

`python scripts/build-emoji-catalog.py` regenerates
`app/src/main/assets/emoji/catalog.tsv`. It downloads only missing source files,
keeps Unicode category/order, excludes standalone component rows, and joins
CLDR keywords without variation selectors. Skin-tone sequences retain their
original Unicode values and have a base-family lookup key for the picker.
The generator adds a small, separately listed first-party alias set for everyday
searches such as `happy`, `lol`, `thanks`, and `usa`.
The shipped catalog has 3,781 sequences, including skin-tone variants.
