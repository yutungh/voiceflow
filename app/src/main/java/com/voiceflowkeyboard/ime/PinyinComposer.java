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

    /** Partial readings retained per position during the Viterbi pass. */
    private static final int BEAM_WIDTH = 6;

    /** Dictionary entries considered per key when extending a path. */
    private static final int ENTRIES_PER_SEGMENT = 4;

    private final PinyinLookup dictionary;

    /**
     * log(totalFrequency) — the unigram normaliser, and therefore the implicit
     * cost of opening another segment. Derived from the shipped data rather
     * than hand-tuned.
     */
    private final double logTotalFrequency;

    PinyinComposer(PinyinLookup dictionary) {
        this.dictionary = dictionary;
        this.logTotalFrequency = Math.log(Math.max(1L, dictionary.totalFrequency()));
    }

    /** The lookup behind this composer; the 9-key layout asks it for spellings. */
    PinyinLookup lookup() {
        return dictionary;
    }

    /** Unigram log-probability of one dictionary entry. Always negative. */
    private double logProbability(int frequency) {
        return Math.log(frequency + 1.0) - logTotalFrequency;
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

        addFullReadings(input, paths, results, seen);
        if (dictionary.offersLeadingWords()) {
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
        for (int end = longest; end > 0 && results.size() < limit; end--) {
            if (boundaries != null && !boundaries[end]) {
                continue;
            }
            List<PinyinDictionary.Entry> entries = dictionary.entriesFor(input.substring(0, end));
            int consumed = end < input.length() && input.charAt(end) == '\'' ? end + 1 : end;
            int taken = 0;
            for (PinyinDictionary.Entry entry : entries) {
                if (taken >= 3 || results.size() >= limit) {
                    break;
                }
                if (seen.add(entry.word)) {
                    results.add(new PinyinCandidate(entry.word, consumed, false,
                            logProbability(entry.frequency), keyOf(entry, input.substring(0, end))));
                    taken++;
                }
            }
        }
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
            paths.add(new ArrayList<>(BEAM_WIDTH));
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
                String span = input.substring(start, end);
                List<PinyinDictionary.Entry> entries = dictionary.entriesFor(span);
                if (entries.isEmpty()) {
                    continue;
                }
                int considered = Math.min(entries.size(), ENTRIES_PER_SEGMENT);
                for (int e = 0; e < considered; e++) {
                    PinyinDictionary.Entry entry = entries.get(e);
                    double gain = logProbability(entry.frequency);
                    String key = keyOf(entry, span);
                    for (Path prefix : prefixPaths) {
                        String spelling = prefix.spelling.isEmpty() ? key : prefix.spelling + "'" + key;
                        offer(paths.get(end), new Path(prefix.text + entry.word, prefix.score + gain, spelling));
                    }
                }
            }
        }
        return paths;
    }

    /** Keeps a beam sorted by descending score, bounded at {@link #BEAM_WIDTH}. */
    private static void offer(List<Path> beam, Path candidate) {
        for (int i = 0; i < beam.size(); i++) {
            if (beam.get(i).text.equals(candidate.text)) {
                if (candidate.score > beam.get(i).score) {
                    beam.set(i, candidate);
                    beam.sort((a, b) -> Double.compare(b.score, a.score));
                }
                return;
            }
        }
        if (beam.size() < BEAM_WIDTH) {
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
            Set<String> seen
    ) {
        Map<String, Path> scored = new HashMap<>();
        for (Path path : paths.get(input.length())) {
            merge(scored, path);
        }
        for (PinyinDictionary.Entry entry : dictionary.entriesFor(input)) {
            merge(scored, new Path(entry.word, logProbability(entry.frequency), keyOf(entry, input)));
        }
        emit(scored, input.length(), false, results, seen, Integer.MAX_VALUE);
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
