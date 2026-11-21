package com.voiceflowkeyboard.ime;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 9-key (T9) view of the dictionary: every letter key projected onto the phone
 * keypad, so 你好 ("nihao") is reachable by typing 6-4-4-2-6.
 *
 * <p>Building this costs a projection and a sort over every key, so it is
 * created on demand — see {@link #build} — rather than at dictionary load. Most
 * sessions never touch 9-key and should not pay for it.
 *
 * <p>Instances are immutable once built and safe to publish to the UI thread.
 *
 * <p>Two things make 9-key affordable despite being far more ambiguous than
 * QWERTY. Each digit key remembers its best entry up front, so offering
 * completions for a one-digit prefix is a scan of precomputed pairs rather than
 * thousands of payload parses. And the letter keys behind a digit key are held
 * as indices into the dictionary, so no duplicate strings are retained; the
 * digit keys themselves are one byte array with offsets (0.8.9, when the
 * dictionary grew sevenfold).
 *
 * <p>0.8.9 also adds initials-only readings (简拼): the commonest words of two
 * to four characters can be typed by the first letter of each syllable, so
 * 96 offers 我们 (w-m). They are discounted far below any full reading of the
 * same digits, so they surface only after the full readings, and only where
 * the digits also spell pinyin.
 */
final class PinyinDigitIndex implements PinyinLookup {

    /** Standard phone keypad mapping; index 0 is 'a'. */
    private static final char[] LETTER_TO_DIGIT = {
            '2', '2', '2',            // a b c
            '3', '3', '3',            // d e f
            '4', '4', '4',            // g h i
            '5', '5', '5',            // j k l
            '6', '6', '6',            // m n o
            '7', '7', '7', '7',       // p q r s
            '8', '8', '8',            // t u v
            '9', '9', '9', '9'        // w x y z
    };

    /** Words considered for initials-only readings, by frequency. */
    static final int ABBREVIATED_WORDS = 20000;
    /** Readings kept per initials digit string. */
    private static final int ABBREVIATIONS_PER_KEY = 12;
    /**
     * An initials-only reading counts as its word's frequency divided by this:
     * typing "wm" is a much weaker signal for 我们 than typing "women".
     */
    static final int ABBREVIATION_DISCOUNT = 2000;

    private final PinyinDictionary dictionary;
    /** Every distinct digit key, concatenated; group g is digits[digitStart[g], digitStart[g+1]). */
    private final byte[] digits;
    private final int[] digitStart;
    private final int[] letterKeyStart;
    private final int[] letterKeyIndices;
    private final int[] bestKey;
    private final int[] bestFrequency;
    private final int maxSpanLength;
    private final Map<String, List<PinyinDictionary.Entry>> abbreviations;

    private PinyinDigitIndex(
            PinyinDictionary dictionary,
            byte[] digits,
            int[] digitStart,
            int[] letterKeyStart,
            int[] letterKeyIndices,
            int[] bestKey,
            int[] bestFrequency,
            int maxSpanLength,
            Map<String, List<PinyinDictionary.Entry>> abbreviations
    ) {
        this.dictionary = dictionary;
        this.digits = digits;
        this.digitStart = digitStart;
        this.letterKeyStart = letterKeyStart;
        this.letterKeyIndices = letterKeyIndices;
        this.bestKey = bestKey;
        this.bestFrequency = bestFrequency;
        this.maxSpanLength = maxSpanLength;
        this.abbreviations = abbreviations;
    }

    static char digitFor(char letter) {
        if (letter >= 'a' && letter <= 'z') {
            return LETTER_TO_DIGIT[letter - 'a'];
        }
        if (letter >= 'A' && letter <= 'Z') {
            return LETTER_TO_DIGIT[letter - 'A'];
        }
        return 0;
    }

    static String project(String letterKey) {
        StringBuilder projected = new StringBuilder(letterKey.length());
        for (int i = 0; i < letterKey.length(); i++) {
            char digit = digitFor(letterKey.charAt(i));
            if (digit != 0) {
                projected.append(digit);
            }
        }
        return projected.toString();
    }

    /** Projects the whole dictionary. Call off the main thread. */
    static PinyinDigitIndex build(PinyinDictionary dictionary) {
        int keyCount = dictionary.keyCount();
        Integer[] order = new Integer[keyCount];
        String[] letters = new String[keyCount];
        String[] projected = new String[keyCount];
        int maxSpan = 0;
        for (int i = 0; i < keyCount; i++) {
            order[i] = i;
            letters[i] = dictionary.keyAt(i);
            projected[i] = project(letters[i]);
            maxSpan = Math.max(maxSpan, projected[i].length());
        }
        Arrays.sort(order, (a, b) -> {
            int byDigits = projected[a].compareTo(projected[b]);
            return byDigits != 0 ? byDigits : letters[a].compareTo(letters[b]);
        });

        int[] letterIndices = new int[keyCount];
        int[] starts = new int[keyCount + 1];
        int groupCount = 0;
        int digitBytes = 0;
        String previous = null;
        for (int position = 0; position < keyCount; position++) {
            int keyIndex = order[position];
            letterIndices[position] = keyIndex;
            String digitKey = projected[keyIndex];
            if (!digitKey.equals(previous)) {
                starts[groupCount++] = position;
                digitBytes += digitKey.length();
                previous = digitKey;
            }
        }
        int[] groupStart = Arrays.copyOf(starts, groupCount + 1);
        groupStart[groupCount] = keyCount;

        byte[] digitData = new byte[digitBytes];
        int[] digitStart = new int[groupCount + 1];
        int at = 0;
        for (int g = 0; g < groupCount; g++) {
            digitStart[g] = at;
            String digitKey = projected[letterIndices[groupStart[g]]];
            for (int i = 0; i < digitKey.length(); i++) {
                digitData[at++] = (byte) digitKey.charAt(i);
            }
        }
        digitStart[groupCount] = at;

        int[] bestKey = new int[groupCount];
        int[] bestFrequency = new int[groupCount];
        int[] keyBest = new int[keyCount];
        for (int g = 0; g < groupCount; g++) {
            for (int p = groupStart[g]; p < groupStart[g + 1]; p++) {
                int frequency = dictionary.bestFrequencyAt(letterIndices[p]);
                keyBest[letterIndices[p]] = frequency;
                if (frequency > bestFrequency[g]) {
                    bestKey[g] = letterIndices[p];
                    bestFrequency[g] = frequency;
                }
            }
        }

        return new PinyinDigitIndex(dictionary, digitData, digitStart, groupStart, letterIndices,
                bestKey, bestFrequency, maxSpan, buildAbbreviations(dictionary, letters, keyBest));
    }

    /**
     * Initials-only readings for the commonest two- to four-character words:
     * each word's first entry per key, split into as many syllables as it has
     * characters, keyed by the digits of the syllables' first letters.
     */
    private static Map<String, List<PinyinDictionary.Entry>> buildAbbreviations(
            PinyinDictionary dictionary, String[] letters, int[] keyBest) {
        Integer[] byFrequency = new Integer[letters.length];
        int candidates = 0;
        for (int i = 0; i < letters.length; i++) {
            if (keyBest[i] > 0 && letters[i].length() >= 2) {
                byFrequency[candidates++] = i;
            }
        }
        Arrays.sort(byFrequency, 0, candidates, (a, b) -> Integer.compare(keyBest[b], keyBest[a]));
        Map<String, String> readsAs = charactersBySyllable(dictionary);
        Map<String, List<PinyinDictionary.Entry>> found = new HashMap<>();
        int taken = 0;
        for (int c = 0; c < candidates && taken < ABBREVIATED_WORDS; c++) {
            int keyIndex = byFrequency[c];
            PinyinDictionary.Entry best = dictionary.firstEntryAt(keyIndex);
            int characters = best == null ? 0 : best.word.codePointCount(0, best.word.length());
            if (characters < 2 || characters > 4) {
                continue;
            }
            String initials = initials(dictionary, readsAs, letters[keyIndex], best.word);
            if (initials == null) {
                continue;
            }
            taken++;
            List<PinyinDictionary.Entry> list = found.computeIfAbsent(project(initials), k -> new ArrayList<>());
            if (list.size() < ABBREVIATIONS_PER_KEY) {
                list.add(new PinyinDictionary.Entry(best.word,
                        Math.max(1, best.frequency / ABBREVIATION_DISCOUNT), initials));
            }
        }
        return found;
    }

    /** Every single character the dictionary reads as each syllable ("ke" -> 可克课...). */
    static Map<String, String> charactersBySyllable(PinyinDictionary dictionary) {
        Map<String, String> readsAs = new HashMap<>();
        for (String syllable : dictionary.syllables()) {
            StringBuilder single = new StringBuilder();
            for (PinyinDictionary.Entry entry : dictionary.entriesFor(syllable)) {
                if (entry.word.codePointCount(0, entry.word.length()) == 1) {
                    single.append(entry.word);
                }
            }
            readsAs.put(syllable, single.toString());
        }
        return readsAs;
    }

    /**
     * First letters of {@code key} cut into one syllable per character of
     * {@code word}, each a reading of its character, so 可能 is ke'neng (kn),
     * never ken'eng. Null when there is no such cut.
     */
    static String initials(PinyinDictionary dictionary, Map<String, String> readsAs, String key, String word) {
        int[] characters = word.codePoints().toArray();
        StringBuilder out = new StringBuilder(characters.length);
        return cut(dictionary, readsAs, key, characters, 0, 0, out) ? out.toString() : null;
    }

    private static boolean cut(PinyinDictionary dictionary, Map<String, String> readsAs, String key,
                               int[] characters, int at, int from, StringBuilder out) {
        if (at == characters.length) {
            return from == key.length();
        }
        String character = new String(Character.toChars(characters[at]));
        for (int end = Math.min(key.length(), from + 6); end > from; end--) {
            String syllable = key.substring(from, end);
            String reads = readsAs.get(syllable);
            if (dictionary.isSyllable(syllable) && reads != null && reads.contains(character)) {
                out.append(key.charAt(from));
                if (cut(dictionary, readsAs, key, characters, at + 1, end, out)) {
                    return true;
                }
                out.setLength(out.length() - 1);
            }
        }
        return false;
    }

    int digitKeyCount() {
        return digitStart.length - 1;
    }

    @Override
    public int maxSpanLength() {
        return maxSpanLength;
    }

    @Override
    public long totalFrequency() {
        return dictionary.totalFrequency();
    }

    @Override
    public String normalize(String rawInput, int maxLength) {
        if (rawInput == null) {
            return "";
        }
        StringBuilder cleaned = new StringBuilder(rawInput.length());
        for (int i = 0; i < rawInput.length() && cleaned.length() < maxLength; i++) {
            char c = rawInput.charAt(i);
            if (c >= '2' && c <= '9') {
                cleaned.append(c);
            }
        }
        return cleaned.toString();
    }

    @Override
    public List<PinyinDictionary.Entry> entriesFor(String span) {
        int group = groupOf(span);
        if (group < 0) {
            // Initials alone never turn digits that spell no pinyin (4426) into
            // words: those stay as typed, so a slip is never silently "read".
            return Collections.emptyList();
        }
        List<PinyinDictionary.Entry> merged = new ArrayList<>();
        for (int p = letterKeyStart[group]; p < letterKeyStart[group + 1]; p++) {
            merged.addAll(dictionary.entriesAt(letterKeyIndices[p]));
        }
        List<PinyinDictionary.Entry> abbreviated = abbreviations.get(span);
        if (abbreviated != null) {
            merged.addAll(abbreviated);
        }
        Collections.sort(merged, (a, b) -> Integer.compare(b.frequency, a.frequency));
        return merged;
    }

    @Override
    public List<PinyinDictionary.Entry> completionsFor(String prefix, int limit) {
        if (limit <= 0) {
            return Collections.emptyList();
        }
        int start = firstGroupWithPrefix(prefix);
        if (start < 0) {
            return Collections.emptyList();
        }
        // Rank the groups on their precomputed frequency and parse only the
        // winners: a one-digit prefix spans thousands of groups.
        PinyinDictionary.TopK top = new PinyinDictionary.TopK(limit);
        for (int g = start; g < groupCount() && groupStartsWith(g, prefix); g++) {
            if (groupLength(g) != prefix.length() && bestFrequency[g] > 0) {
                top.offer(g, bestFrequency[g]);
            }
        }
        List<PinyinDictionary.Entry> found = new ArrayList<>(top.size);
        for (int g : top.ranked()) {
            PinyinDictionary.Entry best = dictionary.firstEntryAt(bestKey[g]);
            if (best != null) {
                found.add(best);
            }
        }
        return found;
    }

    // ------------------------------------------------- for PinyinKeypadLookup

    PinyinDictionary dictionary() {
        return dictionary;
    }

    private int compareGroup(int group, String key) {
        int from = digitStart[group];
        int length = digitStart[group + 1] - from;
        int shared = Math.min(length, key.length());
        for (int i = 0; i < shared; i++) {
            int difference = digits[from + i] - key.charAt(i);
            if (difference != 0) {
                return difference;
            }
        }
        return length - key.length();
    }

    private int search(String key) {
        int low = 0;
        int high = groupCount() - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            int compared = compareGroup(middle, key);
            if (compared < 0) {
                low = middle + 1;
            } else if (compared > 0) {
                high = middle - 1;
            } else {
                return middle;
            }
        }
        return -(low + 1);
    }

    /** Index of the digit group spelled exactly by {@code digits}, or -1. */
    int groupOf(String digitKey) {
        int group = search(digitKey);
        return group < 0 ? -1 : group;
    }

    /** First group whose digits start with {@code prefix}, or -1. */
    int firstGroupWithPrefix(String prefix) {
        int index = search(prefix);
        int start = index >= 0 ? index : -(index + 1);
        return start < groupCount() && groupStartsWith(start, prefix) ? start : -1;
    }

    int groupCount() {
        return digitStart.length - 1;
    }

    String groupDigits(int group) {
        return new String(digits, digitStart[group], groupLength(group), StandardCharsets.US_ASCII);
    }

    int groupLength(int group) {
        return digitStart[group + 1] - digitStart[group];
    }

    boolean groupStartsWith(int group, String prefix) {
        if (groupLength(group) < prefix.length()) {
            return false;
        }
        int from = digitStart[group];
        for (int i = 0; i < prefix.length(); i++) {
            if (digits[from + i] != prefix.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    int groupStart(int group) {
        return letterKeyStart[group];
    }

    int groupEnd(int group) {
        return letterKeyStart[group + 1];
    }

    /** Dictionary key index at a position inside a group's range. */
    int keyIndexAt(int position) {
        return letterKeyIndices[position];
    }
}
