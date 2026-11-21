package com.voiceflowkeyboard.ime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Read-only pinyin lookup table backed by assets/pinyin_dict.txt, which
 * scripts/build-pinyin-dict.mjs generates from the pinned Wanxiang (万象)
 * dictionaries (CC BY 4.0, 0.8.9; third_party/README.md).
 *
 * <p>Deliberately free of Android dependencies so the composer can be unit
 * tested on the JVM. The caller supplies the stream: AssetManager on device, a
 * plain file in tests.
 *
 * <p>Keys are the entry's pinyin with syllable spaces removed, which is exactly
 * what the user types, and are stored sorted so lookups are a binary search.
 *
 * <p>0.8.9: the asset grew from 39k to ~270k keys, so it is no longer split into
 * a String per key and per payload (that alone was ~50 MB of heap). The file's
 * bytes are kept as they are, with three offsets per line, and a payload is
 * decoded only when its key is actually hit.
 */
final class PinyinDictionary implements PinyinLookup {

    static final class Entry {
        final String word;
        final int frequency;
        /**
         * The letter key this entry was read from ("nihao"), or null when it was
         * built without one. The 9-key layout shows it as the spelling of the
         * chosen reading.
         */
        final String key;

        Entry(String word, int frequency) {
            this(word, frequency, null);
        }

        Entry(String word, int frequency, String key) {
            this.word = word;
            this.frequency = frequency;
            this.key = key;
        }
    }

    /** The asset as read: ASCII keys, a tab, a UTF-8 payload, a newline. */
    private final byte[] data;
    /** Per key: where it starts, where its tab is, where its line ends. */
    private final int[] keyStart;
    private final int[] tab;
    private final int[] lineEnd;
    /** Per key: the frequency of its leading (best) entry, read once at load. */
    private final int[] best;
    private final Set<String> syllables;
    private final int maxKeyLength;
    private final long totalFrequency;

    private PinyinDictionary(
            byte[] data,
            int[] keyStart,
            int[] tab,
            int[] lineEnd,
            Set<String> syllables,
            int maxKeyLength,
            long totalFrequency
    ) {
        this.data = data;
        this.keyStart = keyStart;
        this.tab = tab;
        this.lineEnd = lineEnd;
        this.best = new int[keyStart.length];
        for (int i = 0; i < keyStart.length; i++) {
            best[i] = leadingFrequency(data, tab[i] + 1, lineEnd[i]);
        }
        this.syllables = syllables;
        this.maxKeyLength = maxKeyLength;
        this.totalFrequency = totalFrequency;
    }

    static PinyinDictionary load(InputStream stream) throws IOException {
        byte[] data = readAll(stream);
        IntList starts = new IntList();
        IntList tabs = new IntList();
        IntList ends = new IntList();
        Set<String> syllableSet = new HashSet<>();
        int declaredMaxKey = 0;
        long declaredTotalFrequency = 0L;

        int position = 0;
        while (position < data.length) {
            int end = position;
            while (end < data.length && data[end] != '\n') {
                end++;
            }
            int contentEnd = end > position && data[end - 1] == '\r' ? end - 1 : end;
            if (contentEnd > position) {
                if (data[position] == '#') {
                    String line = new String(data, position, contentEnd - position, StandardCharsets.UTF_8);
                    if (line.startsWith("#syllables=")) {
                        for (String syllable : line.substring("#syllables=".length()).split(" ")) {
                            if (!syllable.isEmpty()) {
                                syllableSet.add(syllable);
                            }
                        }
                    } else if (line.startsWith("#maxkey=")) {
                        try {
                            declaredMaxKey = Integer.parseInt(line.substring("#maxkey=".length()).trim());
                        } catch (NumberFormatException ignored) {
                            // Fall back to measuring the keys below.
                        }
                    } else if (line.startsWith("#totalfreq=")) {
                        try {
                            declaredTotalFrequency =
                                    Long.parseLong(line.substring("#totalfreq=".length()).trim());
                        } catch (NumberFormatException ignored) {
                            // Fall back to the summed default below.
                        }
                    }
                } else {
                    int t = position;
                    while (t < contentEnd && data[t] != '\t') {
                        t++;
                    }
                    if (t > position && t < contentEnd - 1) {
                        if (starts.size > 0 && compareKeys(data, starts.get(starts.size - 1),
                                tabs.get(tabs.size - 1), data, position, t) >= 0) {
                            throw new IOException("pinyin asset keys are not sorted at byte " + position);
                        }
                        starts.add(position);
                        tabs.add(t);
                        ends.add(contentEnd);
                    }
                }
            }
            position = end + 1;
        }

        int count = starts.size;
        int maxKeyLength = declaredMaxKey;
        if (maxKeyLength <= 0) {
            for (int i = 0; i < count; i++) {
                maxKeyLength = Math.max(maxKeyLength, tabs.get(i) - starts.get(i));
            }
        }
        PinyinDictionary dictionary = new PinyinDictionary(data, starts.toArray(), tabs.toArray(), ends.toArray(),
                syllableSet, maxKeyLength, Math.max(1L, declaredTotalFrequency));
        if (declaredTotalFrequency <= 0L) {
            // Header missing or corrupt: fall back to a positive value so the
            // composer's log-probability maths stays finite.
            long total = 1L;
            for (int i = 0; i < count; i++) {
                for (Entry entry : dictionary.entriesAt(i)) {
                    total += entry.frequency;
                }
            }
            return new PinyinDictionary(data, dictionary.keyStart, dictionary.tab, dictionary.lineEnd,
                    syllableSet, maxKeyLength, total);
        }
        return dictionary;
    }

    private static byte[] readAll(InputStream stream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
        byte[] chunk = new byte[1 << 16];
        int read;
        while ((read = stream.read(chunk)) > 0) {
            out.write(chunk, 0, read);
        }
        return out.toByteArray();
    }

    /** A growable int array, to avoid boxing ~270k offsets three times over. */
    private static final class IntList {
        int[] values = new int[1 << 12];
        int size;

        void add(int value) {
            if (size == values.length) {
                int[] grown = new int[values.length * 2];
                System.arraycopy(values, 0, grown, 0, size);
                values = grown;
            }
            values[size++] = value;
        }

        int get(int index) {
            return values[index];
        }

        int[] toArray() {
            int[] out = new int[size];
            System.arraycopy(values, 0, out, 0, size);
            return out;
        }
    }

    /** The number after the first record's last ':' in data[from, end), or 0 when the record is malformed. */
    private static int leadingFrequency(byte[] data, int from, int end) {
        int stop = from;
        while (stop < end && data[stop] != ',') {
            stop++;
        }
        int colon = stop - 1;
        while (colon >= from && data[colon] != ':') {
            colon--;
        }
        int value = 0;
        for (int i = colon + 1; i < stop; i++) {
            int digit = data[i] - '0';
            if (digit < 0 || digit > 9 || value > (Integer.MAX_VALUE - 9) / 10) {
                return 0;
            }
            value = value * 10 + digit;
        }
        // No colon, or an empty word (":100"): not a usable record.
        return colon > from ? value : 0;
    }

    /** Frequency of the best entry of the key at {@code index}, without parsing it. */
    int bestFrequencyAt(int index) {
        return best[index];
    }

    private static int compareKeys(byte[] a, int aFrom, int aTo, byte[] b, int bFrom, int bTo) {
        int length = Math.min(aTo - aFrom, bTo - bFrom);
        for (int i = 0; i < length; i++) {
            int difference = (a[aFrom + i] & 0xff) - (b[bFrom + i] & 0xff);
            if (difference != 0) {
                return difference;
            }
        }
        return (aTo - aFrom) - (bTo - bFrom);
    }

    /** Compares the key at {@code index} with {@code key}, like String.compareTo. */
    private int compareKeyAt(int index, String key) {
        int from = keyStart[index];
        int length = tab[index] - from;
        int shared = Math.min(length, key.length());
        for (int i = 0; i < shared; i++) {
            int difference = (data[from + i] & 0xff) - key.charAt(i);
            if (difference != 0) {
                return difference;
            }
        }
        return length - key.length();
    }

    private boolean keyStartsWith(int index, String prefix) {
        int from = keyStart[index];
        if (tab[index] - from < prefix.length()) {
            return false;
        }
        for (int i = 0; i < prefix.length(); i++) {
            if (data[from + i] != prefix.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /** Index of {@code key}, or {@code -(insertion point) - 1}, as Arrays.binarySearch. */
    private int search(String key) {
        int low = 0;
        int high = keyStart.length - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            int compared = compareKeyAt(middle, key);
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

    int size() {
        return keyStart.length;
    }

    int maxKeyLength() {
        return maxKeyLength;
    }

    @Override
    public int maxSpanLength() {
        return maxKeyLength;
    }

    @Override
    public long totalFrequency() {
        return totalFrequency;
    }

    @Override
    public String normalize(String rawInput, int maxLength) {
        if (rawInput == null) {
            return "";
        }
        // Apostrophes are accepted so typing "xi'an" is never rejected, but they
        // are only a soft separator today: keys are stored with syllable spaces
        // removed, so 西安 and 先 share the key "xian" and an apostrophe cannot
        // tell them apart. Enforcing a hard boundary would exclude the key
        // "xian" outright and lose 西安 altogether. Real disambiguation needs
        // per-entry syllable boundaries in the generated asset.
        StringBuilder cleaned = new StringBuilder(rawInput.length());
        for (int i = 0; i < rawInput.length() && cleaned.length() < maxLength; i++) {
            char c = rawInput.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                cleaned.append((char) (c - 'A' + 'a'));
            } else if (c >= 'a' && c <= 'z') {
                cleaned.append(c);
            }
        }
        return cleaned.toString();
    }

    /** Number of distinct keys; paired with {@link #keyAt} for index building. */
    int keyCount() {
        return keyStart.length;
    }

    String keyAt(int index) {
        return new String(data, keyStart[index], tab[index] - keyStart[index], StandardCharsets.US_ASCII);
    }

    /**
     * Whether the key at {@code index} is at least as long as {@code pattern}
     * and has its letter at every position where the pattern has a letter
     * (digits and other characters match anything). No allocation.
     */
    boolean keyAgrees(int index, String pattern) {
        int from = keyStart[index];
        if (tab[index] - from < pattern.length()) {
            return false;
        }
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c >= 'a' && c <= 'z' && data[from + i] != c) {
                return false;
            }
        }
        return true;
    }

    /** Length of the key at {@code index}, without building it. */
    int keyLengthAt(int index) {
        return tab[index] - keyStart[index];
    }

    List<Entry> entriesAt(int index) {
        return parse(payloadAt(index), keyAt(index));
    }

    private String payloadAt(int index) {
        return new String(data, tab[index] + 1, lineEnd[index] - tab[index] - 1, StandardCharsets.UTF_8);
    }

    /**
     * Highest-frequency entry for the key at {@code index}, parsed without
     * touching the rest of the payload. Payloads are written frequency-ordered,
     * so the leading record is always the best one.
     */
    Entry firstEntryAt(int index) {
        int from = tab[index] + 1;
        int end = from;
        while (end < lineEnd[index] && data[end] != ',') {
            end++;
        }
        return parseFirst(new String(data, from, end - from, StandardCharsets.UTF_8), keyAt(index));
    }

    boolean isSyllable(String candidate) {
        return syllables.contains(candidate);
    }

    int syllableCount() {
        return syllables.size();
    }

    /** Every valid pinyin syllable, from the asset's header. */
    Set<String> syllables() {
        return Collections.unmodifiableSet(syllables);
    }

    /** Entries whose key is exactly {@code key}, already ordered by descending frequency. */
    @Override
    public List<Entry> entriesFor(String key) {
        int index = search(key);
        if (index < 0) {
            return Collections.emptyList();
        }
        return parse(payloadAt(index), key);
    }

    /** True when any key starts with {@code prefix} (including an exact match). */
    boolean hasKeyWithPrefix(String prefix) {
        return firstIndexWithPrefix(prefix) >= 0;
    }

    /**
     * Entries for keys that start with {@code prefix} but are longer than it —
     * the completions offered while the user is still mid-word. Ordered by
     * descending frequency, capped at {@code limit}.
     *
     * <p>Only the best entry of each matching key is considered. A one-character
     * prefix can span thousands of keys, and parsing every record under all of
     * them was measured to be the single most expensive thing the composer does;
     * taking the leading record keeps it cheap and also gives a more varied list
     * than ten readings of the same key would.
     */
    @Override
    public List<Entry> completionsFor(String prefix, int limit) {
        int start = firstIndexWithPrefix(prefix);
        if (start < 0 || limit <= 0) {
            return Collections.emptyList();
        }
        // A one-letter prefix spans tens of thousands of keys (0.8.9): choose
        // the winners on the frequencies read at load, and parse only them.
        TopK top = new TopK(limit);
        for (int i = start; i < keyStart.length && keyStartsWith(i, prefix); i++) {
            if (keyLengthAt(i) != prefix.length() && best[i] > 0) {
                top.offer(i, best[i]);
            }
        }
        List<Entry> found = new ArrayList<>(top.size);
        for (int i : top.ranked()) {
            Entry entry = firstEntryAt(i);
            if (entry != null) {
                found.add(entry);
            }
        }
        return found;
    }

    /**
     * The {@code k} indices with the highest values, by a bounded min-heap;
     * ties keep the earlier index, as the stable sort they replace did.
     */
    static final class TopK {
        private final int[] index;
        private final int[] value;
        int size;

        TopK(int k) {
            index = new int[Math.max(0, k)];
            value = new int[Math.max(0, k)];
        }

        /** Offers in ascending index order. */
        void offer(int i, int v) {
            if (index.length == 0) {
                return;
            }
            if (size < index.length) {
                index[size] = i;
                value[size] = v;
                up(size++);
            } else if (v > value[0]) {
                index[0] = i;
                value[0] = v;
                down(0);
            }
        }

        /** Highest value first; equal values in ascending index. */
        int[] ranked() {
            Integer[] order = new Integer[size];
            for (int j = 0; j < size; j++) {
                order[j] = j;
            }
            java.util.Arrays.sort(order, (a, b) -> value[a] != value[b]
                    ? Integer.compare(value[b], value[a]) : Integer.compare(index[a], index[b]));
            int[] out = new int[size];
            for (int j = 0; j < size; j++) {
                out[j] = index[order[j]];
            }
            return out;
        }

        /** Heap order: smaller value first; among equals the later index is weaker. */
        private boolean weaker(int a, int b) {
            return value[a] != value[b] ? value[a] < value[b] : index[a] > index[b];
        }

        private void up(int j) {
            while (j > 0) {
                int parent = (j - 1) / 2;
                if (!weaker(j, parent)) {
                    return;
                }
                swap(j, parent);
                j = parent;
            }
        }

        private void down(int j) {
            while (true) {
                int left = 2 * j + 1;
                int right = left + 1;
                int smallest = j;
                if (left < size && weaker(left, smallest)) {
                    smallest = left;
                }
                if (right < size && weaker(right, smallest)) {
                    smallest = right;
                }
                if (smallest == j) {
                    return;
                }
                swap(j, smallest);
                j = smallest;
            }
        }

        private void swap(int a, int b) {
            int i = index[a];
            index[a] = index[b];
            index[b] = i;
            int v = value[a];
            value[a] = value[b];
            value[b] = v;
        }
    }

    private int firstIndexWithPrefix(String prefix) {
        int index = search(prefix);
        int start = index >= 0 ? index : -(index + 1);
        return start < keyStart.length && keyStartsWith(start, prefix) ? start : -1;
    }

    /** Parses only the leading record of a payload. Null when it is malformed. */
    private static Entry parseFirst(String payload, String key) {
        int comma = payload.indexOf(',');
        int end = comma < 0 ? payload.length() : comma;
        int colon = payload.lastIndexOf(':', end);
        if (colon <= 0) {
            return null;
        }
        try {
            return new Entry(payload.substring(0, colon), Integer.parseInt(payload.substring(colon + 1, end)), key);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static List<Entry> parse(String payload, String key) {
        List<Entry> entries = new ArrayList<>(4);
        int position = 0;
        while (position < payload.length()) {
            int comma = payload.indexOf(',', position);
            int end = comma < 0 ? payload.length() : comma;
            int colon = payload.lastIndexOf(':', end);
            if (colon > position) {
                try {
                    entries.add(new Entry(
                            payload.substring(position, colon),
                            Integer.parseInt(payload.substring(colon + 1, end)),
                            key
                    ));
                } catch (NumberFormatException ignored) {
                    // Skip a malformed record rather than failing the whole lookup.
                }
            }
            if (comma < 0) {
                break;
            }
            position = comma + 1;
        }
        return entries;
    }
}
