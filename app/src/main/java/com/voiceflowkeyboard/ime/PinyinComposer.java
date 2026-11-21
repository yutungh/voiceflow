package com.voiceflowkeyboard.ime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a raw pinyin buffer into ranked Hanzi candidates.
 *
 * <p>The dictionary caps entries at four syllables, so anything longer has to be
 * assembled from several entries. That is done with a k-best Viterbi pass over
 * the buffer: every position keeps the best few partial readings, each extended
 * by any dictionary key that matches the text starting there.
 *
 * <p>Scoring is a unigram language model: each segment contributes
 * {@code log(frequency / totalFrequency)}, which is negative, so every extra
 * segment makes a reading strictly worse and a known phrase beats the same
 * characters stitched together from commoner parts. Summing raw log-frequencies
 * instead would do the opposite — each additional segment would add a large
 * positive term, and "nihao" would decode as 你 + 哈 + 哦.
 *
 * <p>Candidates come back in three tiers, which is what makes the ordering
 * predictable: readings that consume the whole buffer, then completions that
 * spell out more than was typed, then readings that cover only a leading part
 * of the buffer (the escape hatch when a typo leaves a dangling tail).
 *
 * <p>Every candidate also carries the letters it spells (0.8.7), which the 9-key
 * layout shows as the reading of the digits.
 *
 * <p>Pure Java by design — no Android types — so it is unit tested directly.
 */
final class PinyinComposer {

    /** Longest buffer we will analyse; beyond this the user should commit something. */
    static final int MAX_INPUT_LENGTH = 32;

    /**
     * How a reading is scored (0.8.9). The defaults were calibrated on the dev
     * half of the nine-key benchmark (bench/pinyin/README.md) for the Wanxiang
     * counts; the held-out half was never used to choose them.
     *
     * <p>A segment scores {@code exponent * log(frequency + 1) - segmentCost},
     * where {@code segmentCost = exponent * log(totalFrequency) + extraSegmentCost}.
     * With an exponent of 1 and no extra cost that is exactly the unigram
     * log-probability 0.8.8 used. An exponent below 1 flattens the counts, which
     * matters because Wanxiang's come from written text: 这些 is twenty times
     * 谢谢 there, far more than in chat. The extra cost is the price of a word
     * boundary, the lever that decides between one long word and two short ones.
     */
    static final class Scoring {
        final double exponent;
        final double extraSegmentCost;
        /**
         * Context: a reading that ends the buffer on a sentence-final particle
         * (吗, 吧, 呢, ...). On nine-key 62 is ma or na, and a chat message ending
         * there means 吗 far more often than 那 or 拿.
         */
        final double particleBonus;
        /** Nine-key: one dictionary word that reads the whole buffer. */
        final double wholeWordBonus;
        /** Nine-key: per syllable in an entry's spelling that is rarely meant (n, m, ng, ...). */
        final double rareSyllablePenalty;
        /** Partial readings retained per position during the Viterbi pass. */
        final int beamWidth;
        /** Dictionary entries considered per key when extending a path. */
        final int entriesPerSegment;

        Scoring(double exponent, double extraSegmentCost, double particleBonus, double wholeWordBonus,
                double rareSyllablePenalty, int beamWidth, int entriesPerSegment) {
            this.exponent = exponent;
            this.extraSegmentCost = extraSegmentCost;
            this.particleBonus = particleBonus;
            this.wholeWordBonus = wholeWordBonus;
            this.rareSyllablePenalty = rareSyllablePenalty;
            this.beamWidth = beamWidth;
            this.entriesPerSegment = entriesPerSegment;
        }

        /** What 0.8.8 did: plain unigram log-probabilities, beam 6, 4 entries a segment. */
        static final Scoring UNIGRAM = new Scoring(1.0, 0.0, 0.0, 0.0, 0.0, 6, 4);

        /**
         * Calibrated on the dev split (bench/pinyin/README.md, "Tuning"). The
         * whole-word bonus measured no gain and stays 0; the rare-syllable
         * penalty counts only the vowel-less syllables (PinyinKeypadLookup).
         */
        static final Scoring DEFAULT = new Scoring(0.8, 2.0, 2.0, 0.0, 1.0, 12, 6);

        @Override
        public String toString() {
            return "exp=" + exponent + " seg=" + extraSegmentCost + " particle=" + particleBonus
                    + " whole=" + wholeWordBonus + " rare=" + rareSyllablePenalty
                    + " beam=" + beamWidth + " entries=" + entriesPerSegment;
        }
    }

    /**
     * Sentence-final particles for {@link Scoring#particleBonus}. 啊 and 哦 are
     * left out: their syllables are a single letter (a, o), so a bonus for them
     * lets the pad cut a run anywhere ("zhenma" read as 折磨 + 啊).
     */
    static final String FINAL_PARTICLES = "吗吧呢呀啦嘛呗咯";

    private final PinyinLookup dictionary;
    private final Scoring scoring;

    /**
     * The cost of opening another segment: log(totalFrequency), the unigram
     * normaliser, scaled by the exponent, plus the calibrated extra.
     */
    private final double segmentCost;

    PinyinComposer(PinyinLookup dictionary) {
        this(dictionary, Scoring.DEFAULT);
    }

    PinyinComposer(PinyinLookup dictionary, Scoring scoring) {
        this.dictionary = dictionary;
        this.scoring = scoring;
        this.segmentCost = scoring.exponent * Math.log(Math.max(1L, dictionary.totalFrequency()))
                + scoring.extraSegmentCost;
    }

    /** The lookup behind this composer; the 9-key layout asks it for spellings. */
    PinyinLookup lookup() {
        return dictionary;
    }

    /** Unigram log-score of one dictionary entry. Always negative for the shipped data. */
    private double logProbability(int frequency) {
        return scoring.exponent * Math.log(frequency + 1.0) - segmentCost;
    }

    /** Score of one entry read over input[start, end) of a buffer {@code length} long. */
    private double gain(PinyinDictionary.Entry entry, int start, int end, int length) {
        double gain = logProbability(entry.frequency);
        if (end == length && scoring.particleBonus != 0 && endsWithParticle(entry.word)) {
            gain += scoring.particleBonus;
        }
        if (dictionary.offersLeadingWords()) {
            if (start == 0 && end == length) {
                gain += scoring.wholeWordBonus;
            }
            if (scoring.rareSyllablePenalty != 0) {
                gain -= scoring.rareSyllablePenalty * dictionary.rareSyllables(entry);
            }
        }
        return gain;
    }

    private static boolean endsWithParticle(String word) {
        if (word.isEmpty()) {
            return false;
        }
        int last = word.codePointBefore(word.length());
        return last < 0x10000 && FINAL_PARTICLES.indexOf((char) last) >= 0;
    }

    private static final class Path {
        final String text;
        final double score;
        /** Dictionary keys of the words so far, apostrophe-separated. */
        final String spelling;

        Path(String text, double score, String spelling) {
            this.text = text;
            this.score = score;
            this.spelling = spelling;
        }
    }

    /**
     * Ranked candidates for {@code rawInput}.
     *
     * @param limit maximum number of candidates to return
     */
    List<PinyinCandidate> candidates(String rawInput, int limit) {
        if (rawInput == null || limit <= 0) {
            return Collections.emptyList();
        }

        String input = dictionary.normalize(rawInput, MAX_INPUT_LENGTH);
        if (input.isEmpty()) {
            return Collections.emptyList();
        }

        List<List<Path>> paths = viterbi(input);

        List<PinyinCandidate> results = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        boolean leading = dictionary.offersLeadingWords();
        // 0.8.9: the beam keeps twelve readings, so on the pad the whole readings
        // of a run longer than one syllable could fill the list; leave the
        // leading words their room. One syllable keeps its whole homophone list.
        boolean capWhole = leading && input.length() > MAX_SYLLABLE_KEYS;
        addFullReadings(input, paths, results, seen,
                capWhole ? limit - Math.min(LEADING_WORDS, limit / 3) : Integer.MAX_VALUE);
        if (leading) {
            // Keep room at the end of the list for the words that read just the
            // start of the buffer: they are how a long run is committed piecewise.
            int reserved = Math.min(LEADING_WORDS, Math.max(0, limit - results.size()) / 2);
            addCompletions(input, results, seen, limit - reserved);
            addPartialReadings(input, paths, results, seen, limit - reserved);
            addLeadingWords(input, results, seen, limit);
        } else {
            addCompletions(input, results, seen, limit);
            addPartialReadings(input, paths, results, seen, limit);
        }

        return results.size() > limit ? new ArrayList<>(results.subList(0, limit)) : results;
    }

    /** Keys in the longest syllable (zhuang); a buffer this short may be one syllable. */
    private static final int MAX_SYLLABLE_KEYS = 6;

    /** How many leading-word candidates the 9-key list keeps room for. */
    private static final int LEADING_WORDS = 10;

    /**
     * Tier four, 9-key only: single dictionary words covering a leading stretch
     * of the buffer, longest stretch first. Choosing one commits it and leaves the
     * rest composing. An apostrophe right after the stretch goes with it.
     */
    private void addLeadingWords(
            String input,
            List<PinyinCandidate> results,
            Set<String> seen,
            int limit
    ) {
        int longest = Math.min(dictionary.maxSpanLength(), input.length() - 1);
        boolean[] boundaries = dictionary.spanBoundaries(input);
        // The best word of the shortest stretch (你 for 64426) always keeps a
        // place: it is the one tap that starts committing a run piecewise.
        int shortest = -1;
        for (int end = 1; end <= longest && shortest < 0; end++) {
            if ((boundaries == null || boundaries[end]) && !usefulLeading(leadingEntries(input, end)).isEmpty()) {
                shortest = end;
            }
        }
        for (int end = longest; end > 0 && results.size() < limit; end--) {
            if (boundaries != null && !boundaries[end]) {
                continue;
            }
            if (end > shortest && shortest > 0 && results.size() >= limit - 1) {
                continue;
            }
            List<PinyinDictionary.Entry> entries = usefulLeading(leadingEntries(input, end));
            int consumed = end < input.length() && input.charAt(end) == '\'' ? end + 1 : end;
            int taken = 0;
            for (PinyinDictionary.Entry entry : entries) {
                if (taken >= 3 || results.size() >= limit - (end > shortest && shortest > 0 ? 1 : 0)) {
                    break;
                }
                if (seen.add(entry.word)) {
                    results.add(new PinyinCandidate(entry.word, consumed, false,
                            gain(entry, 0, end, input.length()), keyOf(entry, input.substring(0, end))));
                    taken++;
                }
            }
        }
    }

    /**
     * Leading words worth a place: not a fragment spelled with a vowel-less
     * syllable (嗯 for the 6 of 64...), which would take the slot kept for the
     * first real word (Codex review, 0.8.9).
     */
    private List<PinyinDictionary.Entry> usefulLeading(List<PinyinDictionary.Entry> entries) {
        List<PinyinDictionary.Entry> useful = new ArrayList<>(entries.size());
        for (PinyinDictionary.Entry entry : entries) {
            if (dictionary.rareSyllables(entry) == 0) {
                useful.add(entry);
            }
        }
        return useful;
    }

    /**
     * Entries reading input[0, end). An apostrophe right after the stretch is
     * part of the span, so a syllable pinned whole stays whole (0.8.9: the
     * larger dictionary has 出啊 under chua, which a bare span let through).
     */
    private List<PinyinDictionary.Entry> leadingEntries(String input, int end) {
        int to = end < input.length() && input.charAt(end) == '\'' ? end + 1 : end;
        return dictionary.entriesFor(input.substring(0, to));
    }

    /** Best single reading of the whole buffer, or null when it cannot be covered. */
    String topCandidateText(String rawInput) {
        List<PinyinCandidate> found = candidates(rawInput, 1);
        return found.isEmpty() ? null : found.get(0).text;
    }

    private List<List<Path>> viterbi(String input) {
        int n = input.length();
        List<List<Path>> paths = new ArrayList<>(n + 1);
        for (int i = 0; i <= n; i++) {
            paths.add(new ArrayList<>(scoring.beamWidth));
        }
        paths.get(0).add(new Path("", 0.0, ""));

        int maxKey = Math.min(dictionary.maxSpanLength(), n);
        boolean[] boundaries = dictionary.spanBoundaries(input);
        for (int start = 0; start < n; start++) {
            List<Path> prefixPaths = paths.get(start);
            if (prefixPaths.isEmpty() || (boundaries != null && !boundaries[start])) {
                continue;
            }
            for (int length = 1; length <= maxKey && start + length <= n; length++) {
                int end = start + length;
                if (boundaries != null && !boundaries[end]) {
                    continue;
                }
                if (end < n && input.charAt(end) == '\'') {
                    // A word ends with the apostrophe after it, so the span
                    // carries the cut (and a pinned syllable is read whole).
                    continue;
                }
                String span = input.substring(start, end);
                List<PinyinDictionary.Entry> entries = dictionary.entriesFor(span);
                if (entries.isEmpty()) {
                    continue;
                }
                int considered = Math.min(entries.size(), scoring.entriesPerSegment);
                for (int e = 0; e < considered; e++) {
                    PinyinDictionary.Entry entry = entries.get(e);
                    double gain = gain(entry, start, end, n);
                    String key = keyOf(entry, span);
                    for (Path prefix : prefixPaths) {
                        String spelling = prefix.spelling.isEmpty() ? key : prefix.spelling + "'" + key;
                        offer(paths.get(end), new Path(prefix.text + entry.word, prefix.score + gain, spelling), scoring.beamWidth);
                    }
                }
            }
        }
        return paths;
    }

    /** Keeps a beam sorted by descending score, bounded at {@code beamWidth}. */
    private static void offer(List<Path> beam, Path candidate, int beamWidth) {
        for (int i = 0; i < beam.size(); i++) {
            if (beam.get(i).text.equals(candidate.text)) {
                if (candidate.score > beam.get(i).score) {
                    beam.set(i, candidate);
                    beam.sort((a, b) -> Double.compare(b.score, a.score));
                }
                return;
            }
        }
        if (beam.size() < beamWidth) {
            beam.add(candidate);
            beam.sort((a, b) -> Double.compare(b.score, a.score));
            return;
        }
        Path weakest = beam.get(beam.size() - 1);
        if (candidate.score > weakest.score) {
            beam.set(beam.size() - 1, candidate);
            beam.sort((a, b) -> Double.compare(b.score, a.score));
        }
    }

    /**
     * Tier one: readings covering the entire buffer. Every exact entry for the
     * whole buffer is included — that is the homophone list the user expects for
     * a single syllable — alongside the multi-segment paths the beam found.
     */
    private void addFullReadings(
            String input,
            List<List<Path>> paths,
            List<PinyinCandidate> results,
            Set<String> seen,
            int room
    ) {
        Map<String, Path> scored = new HashMap<>();
        for (Path path : paths.get(input.length())) {
            merge(scored, path);
        }
        for (PinyinDictionary.Entry entry : dictionary.entriesFor(input)) {
            merge(scored, new Path(entry.word, gain(entry, 0, input.length(), input.length()), keyOf(entry, input)));
        }
        emit(scored, input.length(), false, results, seen, room);
    }

    /** Tier two: the buffer is a strict prefix of longer dictionary keys. */
    private void addCompletions(
            String input,
            List<PinyinCandidate> results,
            Set<String> seen,
            int limit
    ) {
        if (results.size() >= limit) {
            return;
        }
        Map<String, Path> scored = new HashMap<>();
        for (PinyinDictionary.Entry entry : dictionary.completionsFor(input, limit * 4)) {
            merge(scored, new Path(entry.word, logProbability(entry.frequency), keyOf(entry, "")));
        }
        emit(scored, input.length(), true, results, seen, limit - results.size());
    }

    /**
     * Tier three: nothing covers the whole buffer, so offer the longest leading
     * stretch that does. Lets the user commit the good part of a mistyped run
     * instead of deleting back to the start.
     */
    private void addPartialReadings(
            String input,
            List<List<Path>> paths,
            List<PinyinCandidate> results,
            Set<String> seen,
            int limit
    ) {
        if (results.size() >= limit) {
            return;
        }
        for (int end = input.length() - 1; end > 0; end--) {
            List<Path> beam = paths.get(end);
            if (beam.isEmpty()) {
                continue;
            }
            Map<String, Path> scored = new HashMap<>();
            for (Path path : beam) {
                merge(scored, path);
            }
            emit(scored, end, false, results, seen, limit - results.size());
            return;
        }
    }

    private static String keyOf(PinyinDictionary.Entry entry, String fallback) {
        return entry.key != null ? entry.key : fallback;
    }

    private static void merge(Map<String, Path> scored, Path path) {
        Path existing = scored.get(path.text);
        if (existing == null || path.score > existing.score) {
            scored.put(path.text, path);
        }
    }

    private static void emit(
            Map<String, Path> scored,
            int consumed,
            boolean completion,
            List<PinyinCandidate> results,
            Set<String> seen,
            int room
    ) {
        if (room <= 0) {
            return;
        }
        List<Path> ordered = new ArrayList<>(scored.values());
        ordered.sort((a, b) -> Double.compare(b.score, a.score));
        int added = 0;
        for (Path path : ordered) {
            if (added >= room) {
                return;
            }
            if (seen.add(path.text)) {
                results.add(new PinyinCandidate(path.text, consumed, completion, path.score, path.spelling));
                added++;
            }
        }
    }
}
