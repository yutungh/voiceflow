package com.voiceflowkeyboard.ime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The nine-key (九宫格) reading of a keypad buffer (0.8.7).
 *
 * <p>A keypad buffer is mostly digits (2-9, each standing for three or four
 * letters) but it can also hold letters she pinned down by tapping a spelling
 * ("ni" for 64) and apostrophes, which mark a syllable boundary: the 1 key
 * (分词) types one, and pinning a whole syllable adds one after it. So
 * {@code "ni'426"} means: the first syllable is exactly "ni", then a syllable
 * spelled with g/h/i, a/b/c, m/n/o.
 *
 * <p>Every dictionary key projects onto the keypad one letter per digit, so a
 * buffer position is always one letter of the key. A span therefore matches a
 * key when the key projects to the span's digits, agrees with every pinned
 * letter, and can be cut into valid syllables at every apostrophe. Plain digit
 * spans, the common case, go straight to the precomputed {@link PinyinDigitIndex}.
 *
 * <p>It also answers the two questions the keypad's spelling strip asks: how
 * to show the buffer as letters ({@link #display}), and which spellings the
 * first undecided digits could be ({@link #spellingOptions}).
 *
 * <p>Pure Java, no Android types; immutable once built.
 */
final class PinyinKeypadLookup implements PinyinLookup {

    static final char SEPARATOR = '\'';

    /** Longest syllable in Hanyu Pinyin ("zhuang", "chuang", "shuang"). */
    private static final int MAX_SYLLABLE = 6;

    /** How many spellings the strip offers at most. */
    static final int MAX_OPTIONS = 16;

    /** One spelling she can pin: replaces buffer[start, end) with {@link #replacement}. */
    static final class SpellingOption {
        final String label;
        final int start;
        final int end;
        final String replacement;

        SpellingOption(String label, int start, int end, String replacement) {
            this.label = label;
            this.start = start;
            this.end = end;
            this.replacement = replacement;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final PinyinDigitIndex index;
    private final PinyinDictionary dictionary;
    /** Syllables with a vowel (interjections such as m, n, ng, hm are left out), most used first. */
    private final List<String> syllables;
    private final Map<String, Integer> syllableFrequency;
    /**
     * Every single character the dictionary reads as each syllable ("an" -> 安按暗...).
     * Keys are stored without syllable breaks, so "pingan" alone cannot say
     * whether 平安 is ping'an or pin'gan; its characters can.
     */
    private final Map<String, String> charactersBySyllable;

    /**
     * A syllable is rarely meant on the pad when it has no vowel (n, m, ng, hm:
     * interjections) or its commonest character is used fewer times than this.
     */
    static final int RARE_SYLLABLE_FREQUENCY = 0;

    /** Syllables {@link #rareSyllables} counts. */
    private final java.util.Set<String> rare;

    PinyinKeypadLookup(PinyinDigitIndex index) {
        this(index, RARE_SYLLABLE_FREQUENCY);
    }

    PinyinKeypadLookup(PinyinDigitIndex index, int rareBelow) {
        this.index = index;
        this.dictionary = index.dictionary();
        Map<String, Integer> frequency = new HashMap<>();
        Map<String, String> characters = new HashMap<>();
        List<String> list = new ArrayList<>();
        for (String syllable : dictionary.syllables()) {
            List<PinyinDictionary.Entry> entries = dictionary.entriesFor(syllable);
            StringBuilder single = new StringBuilder();
            for (PinyinDictionary.Entry entry : entries) {
                if (entry.word.codePointCount(0, entry.word.length()) == 1) {
                    single.append(entry.word);
                }
            }
            characters.put(syllable, single.toString());
            if (!hasVowel(syllable)) {
                continue;
            }
            frequency.put(syllable, entries.isEmpty() ? 0 : entries.get(0).frequency);
            list.add(syllable);
        }
        this.charactersBySyllable = characters;
        Collections.sort(list, (a, b) -> {
            int byFrequency = Integer.compare(frequency.get(b), frequency.get(a));
            return byFrequency != 0 ? byFrequency : a.compareTo(b);
        });
        this.syllables = Collections.unmodifiableList(list);
        this.syllableFrequency = frequency;
        java.util.Set<String> rareSet = new java.util.HashSet<>();
        for (String syllable : dictionary.syllables()) {
            Integer used = frequency.get(syllable);
            if (used == null || used < rareBelow) {
                rareSet.add(syllable);
            }
        }
        this.rare = rareSet;
    }

    @Override
    public int rareSyllables(PinyinDictionary.Entry entry) {
        if (entry.key == null || rare.isEmpty()) {
            return 0;
        }
        List<String> parts = syllablesOf(entry.key, entry.word.codePointCount(0, entry.word.length()));
        if (parts == null) {
            return 0;
        }
        int count = 0;
        for (String part : parts) {
            if (rare.contains(part)) {
                count++;
            }
        }
        return count;
    }

    private static boolean hasVowel(String syllable) {
        for (int i = 0; i < syllable.length(); i++) {
            if ("aeiouv".indexOf(syllable.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }

    static boolean isDigit(char c) {
        return c >= '2' && c <= '9';
    }

    private static boolean isLetter(char c) {
        return c >= 'a' && c <= 'z';
    }

    // ------------------------------------------------------------ PinyinLookup

    @Override
    public String normalize(String rawInput, int maxLength) {
        if (rawInput == null) {
            return "";
        }
        StringBuilder cleaned = new StringBuilder(rawInput.length());
        for (int i = 0; i < rawInput.length() && cleaned.length() < maxLength; i++) {
            char c = rawInput.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                c = (char) (c - 'A' + 'a');
            }
            if (isDigit(c) || isLetter(c)) {
                cleaned.append(c);
            } else if (c == SEPARATOR && cleaned.length() > 0
                    && cleaned.charAt(cleaned.length() - 1) != SEPARATOR) {
                cleaned.append(c);
            }
        }
        return cleaned.toString();
    }

    @Override
    public int maxSpanLength() {
        // A span can carry an apostrophe between each of its syllables.
        return index.maxSpanLength() + 8;
    }

    @Override
    public long totalFrequency() {
        return index.totalFrequency();
    }

    @Override
    public boolean offersLeadingWords() {
        return true;
    }

    /**
     * A span split into its letters/digits, the cuts its apostrophes ask for,
     * and the syllables pinned whole (letters from a cut, or the start, up to
     * the next apostrophe: "chua'" pins chua as one syllable).
     */
    private static final class Pattern {
        final String chars;
        /** Positions in {@link #chars} where a syllable must end, the trailing one included. */
        final int[] cuts;
        final int[] pinStarts;
        final int[] pinEnds;
        /** Digits only, with no cut inside: the precomputed index answers it. */
        final boolean plainDigits;
        /** The span ends with an apostrophe: a longer key must break there too. */
        final boolean trailingCut;

        Pattern(String chars, int[] cuts, int[] pinStarts, int[] pinEnds, boolean plainDigits, boolean trailingCut) {
            this.chars = chars;
            this.cuts = cuts;
            this.pinStarts = pinStarts;
            this.pinEnds = pinEnds;
            this.plainDigits = plainDigits;
            this.trailingCut = trailingCut;
        }

        boolean constrained() {
            return cuts.length > 0 || pinStarts.length > 0;
        }
    }

    /** Null when the span cannot start a word (it opens with an apostrophe). */
    private static Pattern parse(String span) {
        if (span == null || span.isEmpty() || span.charAt(0) == SEPARATOR) {
            return null;
        }
        StringBuilder chars = new StringBuilder(span.length());
        List<Integer> cuts = new ArrayList<>();
        List<Integer> pinStarts = new ArrayList<>();
        List<Integer> pinEnds = new ArrayList<>();
        boolean allDigits = true;
        boolean innerCut = false;
        int segmentStart = 0;
        boolean segmentLetters = true;
        for (int i = 0; i < span.length(); i++) {
            char c = span.charAt(i);
            if (c == SEPARATOR) {
                int at = chars.length();
                cuts.add(at);
                if (i < span.length() - 1) {
                    innerCut = true;
                }
                if (segmentLetters && at > segmentStart) {
                    pinStarts.add(segmentStart);
                    pinEnds.add(at);
                }
                segmentStart = at;
                segmentLetters = true;
                continue;
            }
            if (!isDigit(c)) {
                allDigits = false;
            }
            if (!isLetter(c)) {
                segmentLetters = false;
            }
            chars.append(c);
        }
        boolean trailingCut = span.charAt(span.length() - 1) == SEPARATOR;
        return new Pattern(chars.toString(), toArray(cuts), toArray(pinStarts), toArray(pinEnds),
                allDigits && !innerCut, trailingCut);
    }

    private static int[] toArray(List<Integer> values) {
        int[] array = new int[values.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = values.get(i);
        }
        return array;
    }

    /**
     * Positions where a word may start or end in the whole buffer: everywhere
     * except inside a syllable she pinned whole, so "chua'" is never read as
     * 出 + 啊 nor offered as 出 alone.
     */
    @Override
    public boolean[] spanBoundaries(String input) {
        boolean[] allowed = new boolean[input.length() + 1];
        java.util.Arrays.fill(allowed, true);
        int segmentStart = 0;
        boolean letters = true;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == SEPARATOR) {
                if (letters && i > segmentStart) {
                    for (int inside = segmentStart + 1; inside < i; inside++) {
                        allowed[inside] = false;
                    }
                }
                segmentStart = i + 1;
                letters = true;
            } else if (!isLetter(c)) {
                letters = false;
            }
        }
        return allowed;
    }

    /**
     * Reads {@code word} over {@code key}, one syllable per character, each
     * character a known reading of its syllable, honouring the cuts and pins.
     * Null when there is no such reading.
     */
    private List<String> readingOf(String word, String key, Pattern constraints) {
        int[] characters = word.codePoints().toArray();
        List<String> reading = new ArrayList<>(characters.length);
        return read(characters, 0, key, 0, constraints, reading) ? reading : null;
    }

    private boolean read(int[] characters, int at, String key, int position, Pattern constraints, List<String> out) {
        if (at == characters.length) {
            return position == key.length();
        }
        int pinnedEnd = -1;
        if (constraints != null) {
            for (int i = 0; i < constraints.pinStarts.length; i++) {
                if (constraints.pinStarts[i] == position) {
                    pinnedEnd = constraints.pinEnds[i];
                }
            }
        }
        String character = new String(Character.toChars(characters[at]));
        for (int end = Math.min(key.length(), position + MAX_SYLLABLE); end > position; end--) {
            if (pinnedEnd >= 0 && end != pinnedEnd) {
                continue;
            }
            if (constraints != null && crossesCut(position, end, constraints.cuts)) {
                continue;
            }
            String syllable = key.substring(position, end);
            String readsAs = charactersBySyllable.get(syllable);
            if (readsAs == null || !readsAs.contains(character)) {
                continue;
            }
            out.add(syllable);
            if (read(characters, at + 1, key, end, constraints, out)) {
                return true;
            }
            out.remove(out.size() - 1);
        }
        return false;
    }

    /**
     * Whether an entry fits the cuts and pins. Its characters decide; for the
     * rare word whose characters are not all listed on their own, the number
     * of syllables the cuts allow is the fallback.
     */
    private boolean fits(PinyinDictionary.Entry entry, String key, Pattern pattern) {
        if (!pattern.constrained()) {
            return true;
        }
        if (readingOf(entry.word, key, pattern) != null) {
            return true;
        }
        if (readingOf(entry.word, key, null) != null) {
            return false;
        }
        long counts = syllableCounts(key, pattern.cuts);
        int characters = entry.word.codePointCount(0, entry.word.length());
        return characters < 63 && (counts & (1L << characters)) != 0;
    }

    private static String project(String chars) {
        StringBuilder digits = new StringBuilder(chars.length());
        for (int i = 0; i < chars.length(); i++) {
            char c = chars.charAt(i);
            digits.append(isDigit(c) ? c : PinyinDigitIndex.digitFor(c));
        }
        return digits.toString();
    }

    /** Pinned letters agree with the key over the pattern's length. */
    private static boolean lettersAgree(String key, String chars) {
        if (key.length() < chars.length()) {
            return false;
        }
        for (int i = 0; i < chars.length(); i++) {
            char c = chars.charAt(i);
            if (isLetter(c) && key.charAt(i) != c) {
                return false;
            }
        }
        return true;
    }

    /** Whether {@code key} can be cut into valid syllables with a cut at every position in {@code cuts}. */
    boolean cutsAt(String key, int[] cuts) {
        return cuts.length == 0 || syllableCounts(key, cuts) != 0;
    }

    /**
     * The numbers of syllables {@code key} can be read as while honouring every
     * cut, as a bit set (bit k set: k syllables). 0 when it cannot be.
     *
     * <p>A key is shared by every word spelled the same, so "xian" holds 先 and
     * 西安 alike. A cut after "xi" leaves only the two-syllable reading, and a
     * word has one character per syllable, so 先 drops out.
     */
    private long syllableCounts(String key, int[] cuts) {
        int n = key.length();
        for (int cut : cuts) {
            if (cut > n) {
                return 0;
            }
        }
        long[] counts = new long[n + 1];
        counts[0] = 1L;
        for (int i = 0; i < n; i++) {
            if (counts[i] == 0) {
                continue;
            }
            for (int j = i + 1; j <= Math.min(n, i + MAX_SYLLABLE); j++) {
                if (crossesCut(i, j, cuts)) {
                    break;
                }
                if (dictionary.isSyllable(key.substring(i, j))) {
                    counts[j] |= counts[i] << 1;
                }
            }
        }
        // No syllable may span a cut, so every reading that reaches the end
        // passes through each cut: counts[n] already honours them all.
        return counts[n];
    }

    /** Keeps the entries that fit the pattern's cuts and pins, in their order. */
    private List<PinyinDictionary.Entry> fitting(String key, List<PinyinDictionary.Entry> entries, Pattern pattern) {
        if (!pattern.constrained()) {
            return entries;
        }
        if (syllableCounts(key, pattern.cuts) == 0) {
            return Collections.emptyList();
        }
        List<PinyinDictionary.Entry> kept = new ArrayList<>(entries.size());
        for (PinyinDictionary.Entry entry : entries) {
            if (fits(entry, key, pattern)) {
                kept.add(entry);
            }
        }
        return kept;
    }

    private static boolean crossesCut(int from, int to, int[] cuts) {
        for (int cut : cuts) {
            if (cut > from && cut < to) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<PinyinDictionary.Entry> entriesFor(String span) {
        Pattern pattern = parse(span);
        if (pattern == null || pattern.chars.isEmpty()) {
            return Collections.emptyList();
        }
        if (pattern.plainDigits) {
            return index.entriesFor(pattern.chars);
        }
        int group = index.groupOf(project(pattern.chars));
        if (group < 0) {
            return Collections.emptyList();
        }
        List<PinyinDictionary.Entry> merged = new ArrayList<>();
        for (int p = index.groupStart(group); p < index.groupEnd(group); p++) {
            int keyIndex = index.keyIndexAt(p);
            String key = dictionary.keyAt(keyIndex);
            if (lettersAgree(key, pattern.chars)) {
                merged.addAll(fitting(key, dictionary.entriesAt(keyIndex), pattern));
            }
        }
        Collections.sort(merged, (a, b) -> Integer.compare(b.frequency, a.frequency));
        return merged;
    }

    @Override
    public List<PinyinDictionary.Entry> completionsFor(String prefix, int limit) {
        Pattern pattern = parse(prefix);
        if (pattern == null || pattern.chars.isEmpty() || limit <= 0) {
            return Collections.emptyList();
        }
        if (pattern.plainDigits && !pattern.trailingCut) {
            return index.completionsFor(pattern.chars, limit);
        }
        String digits = project(pattern.chars);
        int group = index.firstGroupWithPrefix(digits);
        if (group < 0) {
            return Collections.emptyList();
        }
        // 0.8.9 (Codex review): a pinned letter ("s", "a'") can match tens of
        // thousands of keys. Rank them on the frequencies read at load, without
        // building a String per key, and parse only the strongest; a pin that
        // also cuts syllables needs its entries checked, so it looks further.
        boolean constrained = pattern.constrained();
        PinyinDictionary.TopK top = new PinyinDictionary.TopK(constrained ? limit * 8 : limit);
        for (int g = group; g < index.groupCount() && index.groupStartsWith(g, digits); g++) {
            if (index.groupLength(g) == digits.length()) {
                continue;
            }
            for (int p = index.groupStart(g); p < index.groupEnd(g); p++) {
                int keyIndex = index.keyIndexAt(p);
                int frequency = dictionary.bestFrequencyAt(keyIndex);
                if (frequency > 0 && dictionary.keyAgrees(keyIndex, pattern.chars)) {
                    top.offer(keyIndex, frequency);
                }
            }
        }
        List<PinyinDictionary.Entry> found = new ArrayList<>();
        for (int keyIndex : top.ranked()) {
            if (found.size() >= limit) {
                break;
            }
            if (!constrained) {
                PinyinDictionary.Entry best = dictionary.firstEntryAt(keyIndex);
                if (best != null) {
                    found.add(best);
                }
            } else {
                List<PinyinDictionary.Entry> kept =
                        fitting(dictionary.keyAt(keyIndex), dictionary.entriesAt(keyIndex), pattern);
                if (!kept.isEmpty()) {
                    found.add(kept.get(0));
                }
            }
        }
        Collections.sort(found, (a, b) -> Integer.compare(b.frequency, a.frequency));
        return found;
    }

    // ------------------------------------------------------------- spellings

    /**
     * Splits one dictionary key into syllables, fewest first ("xian" stays one
     * syllable, "nihao" is ni+hao). Falls back to the whole key when it does not
     * segment, which the shipped data never does.
     */
    List<String> syllablesOf(String key) {
        int n = key.length();
        int[] best = new int[n + 1];
        int[] from = new int[n + 1];
        java.util.Arrays.fill(best, Integer.MAX_VALUE);
        best[0] = 0;
        for (int i = 0; i < n; i++) {
            if (best[i] == Integer.MAX_VALUE) {
                continue;
            }
            for (int j = i + 1; j <= Math.min(n, i + MAX_SYLLABLE); j++) {
                if (dictionary.isSyllable(key.substring(i, j)) && best[i] + 1 < best[j]) {
                    best[j] = best[i] + 1;
                    from[j] = i;
                }
            }
        }
        List<String> parts = new ArrayList<>();
        if (best[n] == Integer.MAX_VALUE) {
            parts.add(key);
            return parts;
        }
        for (int j = n; j > 0; j = from[j]) {
            parts.add(0, key.substring(from[j], j));
        }
        return parts;
    }

    /**
     * {@code key} as exactly {@code count} syllables, or null when it cannot be.
     * Readings made only of syllables with a vowel win: "xian" in two is xi'an,
     * never xia'n (n alone is the interjection 嗯).
     */
    private List<String> syllablesOf(String key, int count) {
        List<String> voiced = syllablesOf(key, count, true);
        return voiced != null ? voiced : syllablesOf(key, count, false);
    }

    private List<String> syllablesOf(String key, int count, boolean vowelsOnly) {
        if (count <= 0 || key.isEmpty()) {
            return null;
        }
        if (count == 1) {
            boolean fits = dictionary.isSyllable(key) && (!vowelsOnly || hasVowel(key));
            return fits ? new ArrayList<>(Collections.singletonList(key)) : null;
        }
        for (int j = Math.min(MAX_SYLLABLE, key.length() - 1); j >= 1; j--) {
            String head = key.substring(0, j);
            if (!dictionary.isSyllable(head) || (vowelsOnly && !hasVowel(head))) {
                continue;
            }
            List<String> tail = syllablesOf(key.substring(j), count - 1, vowelsOnly);
            if (tail != null) {
                tail.add(0, head);
                return tail;
            }
        }
        return null;
    }

    /**
     * A reading's spelling split into syllables. One character is one
     * syllable, so 西安 (key "xian", two characters) splits xi'an while 先 stays
     * xian. Falls back to the fewest syllables per key.
     */
    List<String> spellingSyllables(String spelling, String text) {
        String[] keys = spelling.split(String.valueOf(SEPARATOR));
        if (text != null && !text.isEmpty()) {
            // Best: each word's characters say where its syllables break (ping'an).
            List<String> read = new ArrayList<>();
            int[] codePoints = text.codePoints().toArray();
            if (readKeys(keys, 0, codePoints, 0, read)) {
                return read;
            }
        }
        int characters = text == null ? 0 : text.codePointCount(0, text.length());
        int[] counts = new int[keys.length];
        if (characters > 0 && chooseCounts(keys, 0, characters, counts)) {
            List<String> syllables = new ArrayList<>();
            for (int i = 0; i < keys.length; i++) {
                List<String> parts = syllablesOf(keys[i], counts[i]);
                if (parts == null) {
                    syllables.clear();
                    break;
                }
                syllables.addAll(parts);
            }
            if (!syllables.isEmpty()) {
                return syllables;
            }
        }
        List<String> fewest = new ArrayList<>();
        for (String key : keys) {
            fewest.addAll(syllablesOf(key));
        }
        return fewest;
    }

    /** Splits the text across the keys, reading each word by its characters. */
    private boolean readKeys(String[] keys, int k, int[] text, int at, List<String> out) {
        if (k == keys.length) {
            return at == text.length;
        }
        for (int length = 1; at + length <= text.length && length <= keys[k].length(); length++) {
            String word = new String(text, at, length);
            List<String> reading = readingOf(word, keys[k], null);
            if (reading == null) {
                continue;
            }
            int mark = out.size();
            out.addAll(reading);
            if (readKeys(keys, k + 1, text, at + length, out)) {
                return true;
            }
            while (out.size() > mark) {
                out.remove(out.size() - 1);
            }
        }
        return false;
    }

    /** Picks a syllable count per key so they add up to {@code remaining}. */
    private boolean chooseCounts(String[] keys, int at, int remaining, int[] counts) {
        if (at == keys.length) {
            return remaining == 0;
        }
        long possible = keys[at].isEmpty() ? 0 : syllableCounts(keys[at], new int[0]);
        for (int count = 1; count <= remaining && count < 63; count++) {
            if ((possible & (1L << count)) == 0) {
                continue;
            }
            counts[at] = count;
            if (chooseCounts(keys, at + 1, remaining - count, counts)) {
                return true;
            }
        }
        return false;
    }

    /** Letter positions (counted without apostrophes) where a new syllable starts in the top reading. */
    private boolean[] syllableStarts(PinyinCandidate top, int letters) {
        boolean[] starts = new boolean[letters + 1];
        int position = 0;
        for (String syllable : spellingSyllables(top.spelling, top.text)) {
            if (position <= letters) {
                starts[position] = true;
            }
            position += syllable.length();
        }
        return starts;
    }

    private static String lettersOnly(String spelling) {
        StringBuilder letters = new StringBuilder(spelling.length());
        for (int i = 0; i < spelling.length(); i++) {
            char c = spelling.charAt(i);
            if (isLetter(c)) {
                letters.append(c);
            }
        }
        return letters.toString();
    }

    /**
     * The buffer as letters, the way the strip and the editor show it:
     * {@code "64426"} with 你好 on top reads {@code "ni'hao"}. Positions the top
     * reading does not cover stay as typed.
     */
    String display(String buffer, PinyinCandidate top) {
        if (buffer == null || buffer.isEmpty()) {
            return "";
        }
        String letters = top == null ? "" : lettersOnly(top.spelling);
        boolean[] starts = top == null ? new boolean[1] : syllableStarts(top, letters.length());
        StringBuilder out = new StringBuilder(buffer.length() + 4);
        int letter = 0;
        boolean pastReading = false;
        for (int i = 0; i < buffer.length(); i++) {
            char c = buffer.charAt(i);
            if (c == SEPARATOR) {
                appendSeparator(out);
                continue;
            }
            if (letter < letters.length()) {
                if (letter > 0 && starts[letter]) {
                    appendSeparator(out);
                }
                out.append(letters.charAt(letter));
            } else {
                if (!pastReading && letter > 0 && letters.length() > 0) {
                    appendSeparator(out);
                }
                pastReading = true;
                out.append(c);
            }
            letter++;
        }
        return out.toString();
    }

    private static void appendSeparator(StringBuilder out) {
        if (out.length() > 0 && out.charAt(out.length() - 1) != SEPARATOR) {
            out.append(SEPARATOR);
        }
    }

    /**
     * Spellings for the first digits still undecided. For {@code "64"}: ni, mi,
     * then the letters m, n, o. A syllable pins its letters and a cut after
     * them; a single letter pins just itself. The syllable the top reading
     * uses comes first, then longer syllables, then the more common.
     */
    List<SpellingOption> spellingOptions(String buffer, PinyinCandidate top) {
        if (buffer == null) {
            return Collections.emptyList();
        }
        int start = -1;
        for (int i = 0; i < buffer.length(); i++) {
            if (isDigit(buffer.charAt(i))) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return Collections.emptyList();
        }
        int end = start;
        while (end < buffer.length() && isDigit(buffer.charAt(end))) {
            end++;
        }
        String run = buffer.substring(start, end);
        int pinnedFrom = start;
        while (pinnedFrom > 0 && isLetter(buffer.charAt(pinnedFrom - 1))) {
            pinnedFrom--;
        }
        String pinned = buffer.substring(pinnedFrom, start);
        boolean followedBySeparator = end < buffer.length() && buffer.charAt(end) == SEPARATOR;

        List<String> fitting = new ArrayList<>();
        for (String syllable : syllables) {
            if (syllable.length() < 2 || !syllable.startsWith(pinned) || syllable.length() <= pinned.length()) {
                continue;
            }
            String rest = syllable.substring(pinned.length());
            if (rest.length() <= run.length() && project(rest).equals(run.substring(0, rest.length()))) {
                fitting.add(syllable);
            }
        }
        // Longer first (they explain more of what was typed), then the more
        // common; the list is already ordered by frequency, so a stable sort
        // by length keeps that order within a length.
        Collections.sort(fitting, (a, b) -> Integer.compare(b.length(), a.length()));
        String lead = topSyllableAt(buffer, pinnedFrom, top);
        if (lead != null && fitting.remove(lead)) {
            fitting.add(0, lead);
        }

        Map<String, SpellingOption> options = new LinkedHashMap<>();
        for (String syllable : fitting) {
            String rest = syllable.substring(pinned.length());
            int stop = start + rest.length();
            boolean needsCut = !(stop == end && followedBySeparator);
            options.put(syllable, new SpellingOption(syllable, start, stop, needsCut ? rest + SEPARATOR : rest));
            if (options.size() >= MAX_OPTIONS) {
                break;
            }
        }
        for (char letter : lettersFor(run.charAt(0))) {
            if (options.size() >= MAX_OPTIONS) {
                break;
            }
            String label = String.valueOf(letter);
            if (options.containsKey(label) || !startsSomeSyllable(pinned + letter)) {
                continue;
            }
            options.put(label, new SpellingOption(label, start, start + 1, label));
        }
        return new ArrayList<>(options.values());
    }

    /** The syllable the top reading puts at {@code letterStart}'s buffer position, if any. */
    private String topSyllableAt(String buffer, int bufferPosition, PinyinCandidate top) {
        if (top == null || top.spelling.isEmpty()) {
            return null;
        }
        int letterPosition = 0;
        for (int i = 0; i < bufferPosition; i++) {
            if (buffer.charAt(i) != SEPARATOR) {
                letterPosition++;
            }
        }
        int position = 0;
        for (String syllable : spellingSyllables(top.spelling, top.text)) {
            if (position == letterPosition) {
                return syllable;
            }
            position += syllable.length();
            if (position > letterPosition) {
                return null;
            }
        }
        return null;
    }

    private boolean startsSomeSyllable(String prefix) {
        for (String syllable : syllables) {
            if (syllable.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    static char[] lettersFor(char digit) {
        switch (digit) {
            case '2': return new char[]{'a', 'b', 'c'};
            case '3': return new char[]{'d', 'e', 'f'};
            case '4': return new char[]{'g', 'h', 'i'};
            case '5': return new char[]{'j', 'k', 'l'};
            case '6': return new char[]{'m', 'n', 'o'};
            case '7': return new char[]{'p', 'q', 'r', 's'};
            case '8': return new char[]{'t', 'u', 'v'};
            case '9': return new char[]{'w', 'x', 'y', 'z'};
            default: return new char[0];
        }
    }

    /** Frequency of a syllable's commonest single character; 0 when unknown. */
    int syllableFrequency(String syllable) {
        Integer frequency = syllableFrequency.get(syllable);
        return frequency == null ? 0 : frequency;
    }
}
