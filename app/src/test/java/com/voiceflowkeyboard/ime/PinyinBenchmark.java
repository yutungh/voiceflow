package com.voiceflowkeyboard.ime;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The nine-key pinyin benchmark (0.8.9), see bench/pinyin/README.md.
 *
 * <p>Each phrase in bench/pinyin/phrases.tsv is typed in full, as nine-key
 * digits ("94394" for 谢谢) and as full-keyboard letters, and an engine's
 * candidate list is scored the way she would use it: is the phrase first, is it
 * in the first five, how many characters of the first candidate are right, and
 * how many extra actions it takes to get the phrase committed (picking a lower
 * candidate, paging, committing it in pieces, pinning a syllable's spelling).
 *
 * <p>Engine-agnostic: anything that turns a buffer into ranked (text, consumed)
 * pairs can be scored. {@link JavaEngine} wraps this app's composer;
 * {@link ExternalEngine} drives any other engine through a line protocol on its
 * stdin and stdout (README), which is how Rime will be scored in phase 3.
 *
 * <p>Run it with {@code ./gradlew testDebugUnitTest --tests '*PinyinBenchmarkTest'
 * -PvfBench=<out dir>}, or {@link #main} for quick iteration.
 */
final class PinyinBenchmark {

    enum Mode {
        T9("t9"), QWERTY("qwerty");

        final String id;

        Mode(String id) {
            this.id = id;
        }
    }

    /** One ranked choice: the text and how much of the buffer it reads. */
    static final class Cand {
        final String text;
        final int consumed;

        Cand(String text, int consumed) {
            this.text = text;
            this.consumed = consumed;
        }
    }

    interface Engine extends AutoCloseable {
        String name();

        /** Ranked candidates for a normalized buffer (digits, letters, apostrophes). */
        List<Cand> candidates(Mode mode, String buffer) throws IOException;

        @Override
        default void close() throws IOException {
        }
    }

    /** This app's engine: the same composers the keyboard builds. */
    static final class JavaEngine implements Engine {
        final PinyinComposer qwerty;
        final PinyinComposer keypad;
        final String name;

        JavaEngine(String name, PinyinDictionary dictionary) {
            this.name = name;
            this.qwerty = new PinyinComposer(dictionary);
            this.keypad = new PinyinComposer(new PinyinKeypadLookup(PinyinDigitIndex.build(dictionary)));
        }

        /** A variant for tuning: the given scoring, and syllables rarer than {@code rareBelow} penalised. */
        JavaEngine(String name, PinyinDictionary dictionary, PinyinDigitIndex index,
                   PinyinComposer.Scoring scoring, int rareBelow) {
            this.name = name;
            this.qwerty = new PinyinComposer(dictionary, scoring);
            this.keypad = new PinyinComposer(new PinyinKeypadLookup(index, rareBelow), scoring);
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public List<Cand> candidates(Mode mode, String buffer) {
            PinyinComposer composer = mode == Mode.T9 ? keypad : qwerty;
            List<Cand> out = new ArrayList<>();
            for (PinyinCandidate candidate : composer.candidates(buffer, CANDIDATES)) {
                out.add(new Cand(candidate.text, candidate.consumed));
            }
            return out;
        }
    }

    /**
     * Any other engine, as a process. For each query it is sent one line,
     * {@code <mode>\t<buffer>}, and answers one line,
     * {@code <text>\t<consumed>\t<text>\t<consumed>...} (empty when it has nothing).
     */
    static final class ExternalEngine implements Engine {
        private final String name;
        private final Process process;
        private final Writer in;
        private final BufferedReader out;

        ExternalEngine(String name, List<String> command) throws IOException {
            this.name = name;
            this.process = new ProcessBuilder(command).redirectErrorStream(false).start();
            this.in = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            this.out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public List<Cand> candidates(Mode mode, String buffer) throws IOException {
            in.write(mode.id + "\t" + buffer + "\n");
            in.flush();
            String line = out.readLine();
            if (line == null) {
                throw new IOException(name + " closed its output");
            }
            List<Cand> found = new ArrayList<>();
            String[] fields = line.isEmpty() ? new String[0] : line.split("\t", -1);
            for (int i = 0; i + 1 < fields.length; i += 2) {
                found.add(new Cand(fields[i], Integer.parseInt(fields[i + 1])));
            }
            return found;
        }

        @Override
        public void close() {
            process.destroy();
        }
    }

    /** Another engine's list after the keyboard's own reranking step ({@link RimeRanking}). */
    static final class RerankedEngine implements Engine {
        private final Engine inner;

        RerankedEngine(Engine inner) {
            this.inner = inner;
        }

        @Override
        public String name() {
            return inner.name();
        }

        @Override
        public List<Cand> candidates(Mode mode, String buffer) throws IOException {
            List<Cand> found = new ArrayList<>(inner.candidates(mode, buffer));
            int n = Math.min(found.size(), RimeRanking.REACH + 1);
            List<String> texts = new ArrayList<>(n);
            int[] consumed = new int[n];
            for (int i = 0; i < n; i++) {
                texts.add(found.get(i).text);
                consumed[i] = found.get(i).consumed;
            }
            int index = RimeRanking.promoted(texts, consumed, buffer.length());
            if (index > 0) {
                found.add(0, found.remove(index));
            }
            return found;
        }

        @Override
        public void close() throws IOException {
            inner.close();
        }
    }

    // ------------------------------------------------------------------ phrases

    static final class Phrase {
        final String group;
        final String split;
        final String hanzi;
        final String[] characters;
        final String[] syllables;
        final Set<String> tags;

        Phrase(String group, String split, String hanzi, String[] syllables, Set<String> tags) {
            this.group = group;
            this.split = split;
            this.hanzi = hanzi;
            this.characters = hanzi.codePoints().mapToObj(Character::toString).toArray(String[]::new);
            this.syllables = syllables;
            this.tags = tags;
        }

        String letters() {
            return String.join("", syllables);
        }

        String digits() {
            return PinyinDigitIndex.project(letters());
        }

        String goldFrom(int character) {
            return String.join("", Arrays.copyOfRange(characters, character, characters.length));
        }
    }

    static List<Phrase> loadPhrases(File file) throws IOException {
        List<Phrase> phrases = new ArrayList<>();
        for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\t", -1);
            Set<String> tags = new HashSet<>();
            if (f.length > 4) {
                for (String tag : f[4].split(",")) {
                    if (!tag.isEmpty()) {
                        tags.add(tag);
                    }
                }
            }
            Phrase phrase = new Phrase(f[0], f[1], f[2], f[3].split(" "), tags);
            if (phrase.characters.length != phrase.syllables.length) {
                throw new IOException("characters and syllables differ: " + line);
            }
            phrases.add(phrase);
        }
        return phrases;
    }

    // ------------------------------------------------------------------- replay

    static final int CANDIDATES = 30;
    /** Candidates one strip page shows before she has to scroll. */
    static final int PAGE = 6;

    /** What typing one phrase in one mode came to. */
    static final class Outcome {
        Phrase phrase;
        Mode mode;
        /** Rank of the whole phrase in the first list, -1 when absent. */
        int rank = -1;
        String top = "";
        double characterAccuracy;
        int corrections;
        boolean failed;
    }

    /**
     * The buffer she has after committing {@code committed} characters, with
     * the next {@code pinned} syllables' spellings pinned (nine-key only).
     */
    static String bufferFor(Mode mode, Phrase p, int committed, int pinned) {
        StringBuilder buffer = new StringBuilder();
        for (int i = committed; i < p.syllables.length; i++) {
            if (mode == Mode.QWERTY) {
                buffer.append(p.syllables[i]);
            } else if (i < committed + pinned) {
                buffer.append(p.syllables[i]).append(PinyinKeypadLookup.SEPARATOR);
            } else {
                buffer.append(PinyinDigitIndex.project(p.syllables[i]));
            }
        }
        return buffer.toString();
    }

    /** What PinyinSession leaves composing after a candidate consuming {@code consumed}. */
    static String rest(String buffer, int consumed) {
        int from = Math.min(Math.max(consumed, 0), buffer.length());
        while (from < buffer.length() && buffer.charAt(from) == PinyinKeypadLookup.SEPARATOR) {
            from++;
        }
        return buffer.substring(from);
    }

    /** Actions to commit the candidate at {@code index}: a tap, plus one for not taking the first, plus pages. */
    static int actionsFor(int index) {
        return 1 + (index > 0 ? 1 : 0) + index / PAGE;
    }

    /** Every engine is judged on the same first {@link #CANDIDATES}, whatever it returns. */
    static List<Cand> capped(List<Cand> list) {
        return list.size() > CANDIDATES ? list.subList(0, CANDIDATES) : list;
    }

    static Outcome replay(Engine engine, Mode mode, Phrase p) throws IOException {
        Outcome o = new Outcome();
        o.phrase = p;
        o.mode = mode;
        String gold = p.hanzi;
        int n = p.characters.length;

        String first = bufferFor(mode, p, 0, 0);
        List<Cand> initial = capped(engine.candidates(mode, first));
        for (int i = 0; i < initial.size(); i++) {
            Cand c = initial.get(i);
            if (c.text.equals(gold) && c.consumed == first.length()) {
                o.rank = i;
                break;
            }
        }
        o.top = initial.isEmpty() ? "" : initial.get(0).text;
        o.characterAccuracy = initial.isEmpty() ? 0.0
                : Math.max(0.0, 1.0 - (double) editDistance(o.top, gold) / n);

        // The cheapest way she could commit the phrase (0.8.9, after Codex's
        // review: a fixed longest-first choice could charge an engine more for
        // offering an extra, lower-ranked correct candidate). States are
        // (characters committed, syllables pinned ahead); moves are committing
        // a correct candidate (actionsFor its rank) or, on the pad, pinning the
        // next syllable's spelling (1). Every move costs at least 1, so a plain
        // Dijkstra over at most (n+1)^2 states.
        int[][] cost = new int[n + 1][n + 1];
        for (int[] row : cost) {
            Arrays.fill(row, Integer.MAX_VALUE);
        }
        boolean[][] done = new boolean[n + 1][n + 1];
        cost[0][0] = 0;
        int actions = -1;
        int bestFailure = Integer.MAX_VALUE;
        while (true) {
            int committed = -1;
            int pinned = -1;
            for (int c = 0; c <= n; c++) {
                for (int q = 0; q <= n; q++) {
                    if (!done[c][q] && cost[c][q] != Integer.MAX_VALUE
                            && (committed < 0 || cost[c][q] < cost[committed][pinned])) {
                        committed = c;
                        pinned = q;
                    }
                }
            }
            if (committed < 0) {
                break;
            }
            done[committed][pinned] = true;
            int here = cost[committed][pinned];
            if (committed == n) {
                actions = here;
                break;
            }
            // Giving up from here: delete what is left and spell it out some
            // other way, two actions a character.
            bestFailure = Math.min(bestFailure, here + 2 * (n - committed));
            String buffer = bufferFor(mode, p, committed, pinned);
            List<Cand> list = committed == 0 && pinned == 0 ? initial : capped(engine.candidates(mode, buffer));
            for (int i = 0; i < list.size(); i++) {
                Cand c = list.get(i);
                int length = c.text.codePointCount(0, c.text.length());
                if (length == 0 || committed + length > n
                        || !c.text.equals(String.join("", Arrays.copyOfRange(p.characters, committed, committed + length)))) {
                    continue;
                }
                int nextPinned = Math.max(0, pinned - length);
                if (!rest(buffer, c.consumed).equals(bufferFor(mode, p, committed + length, nextPinned))) {
                    continue;
                }
                int next = here + actionsFor(i);
                if (next < cost[committed + length][nextPinned]) {
                    cost[committed + length][nextPinned] = next;
                }
            }
            if (mode == Mode.T9 && pinned < n - committed && here + 1 < cost[committed][pinned + 1]) {
                cost[committed][pinned + 1] = here + 1;
            }
        }
        if (actions < 0) {
            o.failed = true;
            actions = bestFailure;
        }
        o.corrections = Math.max(0, actions - 1);
        return o;
    }

    static int editDistance(String a, String b) {
        int[] x = a.codePoints().toArray();
        int[] y = b.codePoints().toArray();
        int[] previous = new int[y.length + 1];
        int[] current = new int[y.length + 1];
        for (int j = 0; j <= y.length; j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= x.length; i++) {
            current[0] = i;
            for (int j = 1; j <= y.length; j++) {
                int substitute = previous[j - 1] + (x[i - 1] == y[j - 1] ? 0 : 1);
                current[j] = Math.min(substitute, Math.min(previous[j] + 1, current[j - 1] + 1));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[y.length];
    }

    // ------------------------------------------------------------------ metrics

    static final class Summary {
        int phrases;
        int characters;
        int top1;
        int top5;
        int recall;
        double characterAccuracy;
        int corrections;
        int failed;

        void add(Outcome o) {
            phrases++;
            characters += o.phrase.characters.length;
            top1 += o.rank == 0 ? 1 : 0;
            top5 += o.rank >= 0 && o.rank < 5 ? 1 : 0;
            recall += o.rank >= 0 ? 1 : 0;
            characterAccuracy += o.characterAccuracy;
            corrections += o.corrections;
            failed += o.failed ? 1 : 0;
        }

        String json() {
            return String.format(Locale.ROOT,
                    "{\"phrases\":%d,\"top1\":%.4f,\"top5\":%.4f,\"recall30\":%.4f,\"charAccuracy\":%.4f,"
                            + "\"correctionsPerPhrase\":%.4f,\"correctionsPer100Chars\":%.3f,\"failed\":%d}",
                    phrases, ratio(top1), ratio(top5), ratio(recall), phrases == 0 ? 0 : characterAccuracy / phrases,
                    phrases == 0 ? 0 : (double) corrections / phrases,
                    characters == 0 ? 0 : 100.0 * corrections / characters, failed);
        }

        String line() {
            return String.format(Locale.ROOT, "n=%3d top1 %5.1f%%  top5 %5.1f%%  char %5.1f%%  corr/phrase %.3f  corr/100ch %6.2f  failed %d",
                    phrases, 100 * ratio(top1), 100 * ratio(top5),
                    phrases == 0 ? 0 : 100 * characterAccuracy / phrases,
                    phrases == 0 ? 0 : (double) corrections / phrases,
                    characters == 0 ? 0 : 100.0 * corrections / characters, failed);
        }

        private double ratio(int count) {
            return phrases == 0 ? 0 : (double) count / phrases;
        }
    }

    /** Slices reported: each split, plus each tag within it. */
    static Map<String, Summary> summarize(List<Outcome> outcomes) {
        Map<String, Summary> slices = new LinkedHashMap<>();
        for (Outcome o : outcomes) {
            List<String> keys = new ArrayList<>();
            keys.add(o.mode.id + "/" + o.phrase.split);
            keys.add(o.mode.id + "/all");
            List<String> tags = new ArrayList<>(o.phrase.tags);
            Collections.sort(tags);
            for (String tag : tags) {
                keys.add(o.mode.id + "/" + o.phrase.split + "/" + tag);
            }
            for (String key : keys) {
                slices.computeIfAbsent(key, k -> new Summary()).add(o);
            }
        }
        Map<String, Summary> sorted = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>(slices.keySet());
        Collections.sort(keys);
        for (String key : keys) {
            sorted.put(key, slices.get(key));
        }
        return sorted;
    }

    // ------------------------------------------------------------------ latency

    /** Per-key time through PinyinSession (candidates, spelling, strip options), in ms. */
    static double[] keyLatencies(PinyinComposer composer, List<Phrase> phrases, Mode mode, int rounds) {
        List<Double> times = new ArrayList<>();
        for (int round = 0; round < rounds; round++) {
            for (Phrase p : phrases) {
                String keys = mode == Mode.T9 ? p.digits() : p.letters();
                PinyinSession session = new PinyinSession(composer);
                for (int i = 0; i < keys.length(); i++) {
                    long start = System.nanoTime();
                    session.append(keys.charAt(i));
                    long elapsed = System.nanoTime() - start;
                    if (round > 0) {
                        times.add(elapsed / 1e6);
                    }
                }
            }
        }
        double[] sorted = new double[times.size()];
        for (int i = 0; i < sorted.length; i++) {
            sorted[i] = times.get(i);
        }
        Arrays.sort(sorted);
        return sorted;
    }

    /**
     * Nine-key buffers with spellings pinned (the slower, constrained path):
     * each phrase with its first k syllables pinned, for every k. In ms.
     */
    static double[] pinnedLatencies(PinyinComposer keypad, List<Phrase> phrases, int rounds) {
        List<Double> times = new ArrayList<>();
        for (int round = 0; round < rounds; round++) {
            for (Phrase p : phrases) {
                for (int k = 1; k <= p.syllables.length; k++) {
                    String buffer = bufferFor(Mode.T9, p, 0, k);
                    long start = System.nanoTime();
                    keypad.candidates(buffer, CANDIDATES);
                    long elapsed = System.nanoTime() - start;
                    if (round > 0) {
                        times.add(elapsed / 1e6);
                    }
                }
            }
        }
        double[] sorted = new double[times.size()];
        for (int i = 0; i < sorted.length; i++) {
            sorted[i] = times.get(i);
        }
        Arrays.sort(sorted);
        return sorted;
    }

    static double percentile(double[] sorted, double q) {
        if (sorted.length == 0) {
            return 0;
        }
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(q * sorted.length) - 1)];
    }

    // --------------------------------------------------------------- reporting

    static List<Outcome> run(Engine engine, List<Phrase> phrases) throws IOException {
        List<Outcome> outcomes = new ArrayList<>();
        for (Mode mode : Mode.values()) {
            for (Phrase p : phrases) {
                outcomes.add(replay(engine, mode, p));
            }
        }
        return outcomes;
    }

    /** Per-phrase rows, the input to {@code compare}: mode, hanzi, split, rank, corrections, failed, top. */
    static void writeRows(List<Outcome> outcomes, File file) throws IOException {
        try (PrintWriter w = new PrintWriter(file, "UTF-8")) {
            w.println("# mode\thanzi\tsplit\ttags\trank\tcorrections\tfailed\tcharAccuracy\ttop");
            for (Outcome o : outcomes) {
                List<String> tags = new ArrayList<>(o.phrase.tags);
                Collections.sort(tags);
                w.printf(Locale.ROOT, "%s\t%s\t%s\t%s\t%d\t%d\t%d\t%.4f\t%s%n", o.mode.id, o.phrase.hanzi,
                        o.phrase.split, String.join(",", tags), o.rank, o.corrections, o.failed ? 1 : 0,
                        o.characterAccuracy, o.top);
            }
        }
    }

    static String json(String engine, Map<String, Summary> slices, Map<String, Object> extra) {
        StringBuilder out = new StringBuilder("{\n  \"engine\": \"" + engine + "\",\n");
        for (Map.Entry<String, Object> e : extra.entrySet()) {
            out.append("  \"").append(e.getKey()).append("\": ").append(e.getValue()).append(",\n");
        }
        out.append("  \"slices\": {\n");
        int i = 0;
        for (Map.Entry<String, Summary> e : slices.entrySet()) {
            out.append("    \"").append(e.getKey()).append("\": ").append(e.getValue().json())
                    .append(++i < slices.size() ? ",\n" : "\n");
        }
        return out.append("  }\n}\n").toString();
    }

    /**
     * Paired comparison of two row files on one mode and split: the change in
     * top-1 and in corrections per phrase, each with a 95% paired bootstrap
     * interval over phrases.
     */
    static String compare(File baseline, File candidate, String mode, String split) throws IOException {
        Map<String, String[]> a = readRows(baseline, mode, split);
        Map<String, String[]> b = readRows(candidate, mode, split);
        List<String> keys = new ArrayList<>(a.keySet());
        keys.retainAll(b.keySet());
        Collections.sort(keys);
        int n = keys.size();
        double[] top1 = new double[n];
        double[] corrections = new double[n];
        for (int i = 0; i < n; i++) {
            String[] x = a.get(keys.get(i));
            String[] y = b.get(keys.get(i));
            top1[i] = (y[4].equals("0") ? 1 : 0) - (x[4].equals("0") ? 1 : 0);
            corrections[i] = Integer.parseInt(y[5]) - Integer.parseInt(x[5]);
        }
        return String.format(Locale.ROOT, "%s/%s n=%d  top1 %+.1f pts %s  corrections/phrase %+.3f %s",
                mode, split, n, 100 * mean(top1), interval(top1, 100), mean(corrections), interval(corrections, 1));
    }

    private static Map<String, String[]> readRows(File file, String mode, String split) throws IOException {
        Map<String, String[]> rows = new HashMap<>();
        for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
            if (line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\t", -1);
            if (f[0].equals(mode) && (split.equals("all") || f[2].equals(split))) {
                rows.put(f[1], f);
            }
        }
        return rows;
    }

    private static double mean(double[] values) {
        double sum = 0;
        for (double v : values) {
            sum += v;
        }
        return values.length == 0 ? 0 : sum / values.length;
    }

    private static String interval(double[] values, double scale) {
        if (values.length == 0) {
            return "[]";
        }
        Random random = new Random(89);
        double[] means = new double[2000];
        for (int r = 0; r < means.length; r++) {
            double sum = 0;
            for (int i = 0; i < values.length; i++) {
                sum += values[random.nextInt(values.length)];
            }
            means[r] = sum / values.length;
        }
        Arrays.sort(means);
        return String.format(Locale.ROOT, "[%+.2f, %+.2f]", scale * means[49], scale * means[1949]);
    }

    // ------------------------------------------------------------------- tuning

    /** Knob values tried by {@link #tune}, in Scoring's order, then the rare-syllable threshold. */
    static final double[][] GRID = {
            {0.5, 0.6, 0.7, 0.8, 0.9, 1.0},           // exponent
            {-6, -4, -2, 0, 2, 4, 6},                 // extra segment cost
            {0, 1, 2, 3, 4, 6},                       // particle bonus
            {0, 0.5, 1, 2, 3},                        // whole-word bonus (pad)
            {0, 1, 2, 4},                             // rare-syllable penalty (pad)
            {4, 6, 8, 12},                            // beam width
            {4, 6, 8},                                // entries per segment
            {0, 1000, 10000, 50000},                  // rare below (pad)
    };

    static PinyinComposer.Scoring scoringOf(double[] v) {
        return new PinyinComposer.Scoring(v[0], v[1], v[2], v[3], v[4], (int) v[5], (int) v[6]);
    }

    /** Dev-only objective: nine-key top-1 minus a fifth of a point per correction a phrase. */
    static double objective(Summary s) {
        return (double) s.top1 / s.phrases - 0.2 * s.corrections / s.phrases;
    }

    /**
     * Coordinate descent over {@link #GRID} on the dev split only, from the
     * 0.8.8 scoring. Prints each improvement; the held-out split is not loaded.
     */
    static void tune(PrintWriter console, File asset, File phrasesFile) throws IOException {
        List<Phrase> dev = new ArrayList<>();
        for (Phrase p : loadPhrases(phrasesFile)) {
            if (p.split.equals("dev")) {
                dev.add(p);
            }
        }
        PinyinDictionary dictionary = loadDictionary(asset);
        PinyinDigitIndex index = PinyinDigitIndex.build(dictionary);
        double[] current = {1.0, 0, 0, 0, 0, 6, 4, 0};
        double best = Double.NEGATIVE_INFINITY;
        for (int pass = 0; pass < 3; pass++) {
            boolean moved = false;
            for (int knob = 0; knob < GRID.length; knob++) {
                for (double value : GRID[knob]) {
                    double[] trial = current.clone();
                    trial[knob] = value;
                    Summary[] s = evaluate(dictionary, index, trial, dev);
                    double score = objective(s[0]);
                    if (score > best + 1e-9) {
                        best = score;
                        current = trial;
                        moved = true;
                        console.println(String.format(Locale.ROOT, "pass %d %s rareBelow=%d  t9 %s | qwerty %s",
                                pass, scoringOf(trial), (int) trial[7], s[0].line(), s[1].line()));
                    }
                }
            }
            if (!moved) {
                break;
            }
        }
        console.println("best " + scoringOf(current) + " rareBelow=" + (int) current[7]);
    }

    /** {t9 dev summary, qwerty dev summary} for one setting. */
    static Summary[] evaluate(PinyinDictionary dictionary, PinyinDigitIndex index, double[] v, List<Phrase> phrases)
            throws IOException {
        JavaEngine engine = new JavaEngine("tune", dictionary, index, scoringOf(v), (int) v[7]);
        Summary t9 = new Summary();
        Summary qwerty = new Summary();
        for (Phrase p : phrases) {
            t9.add(replay(engine, Mode.T9, p));
            qwerty.add(replay(engine, Mode.QWERTY, p));
        }
        return new Summary[]{t9, qwerty};
    }

    static PinyinDictionary loadDictionary(File asset) throws IOException {
        try (InputStream stream = new FileInputStream(asset)) {
            return PinyinDictionary.load(stream);
        }
    }

    /**
     * Scores the dictionary at {@code asset} and writes {@code <name>.json} and
     * {@code <name>.tsv} to {@code outDir}. Returns the text report.
     */
    static String benchmark(String name, File asset, File phrasesFile, File outDir, boolean latency) throws IOException {
        List<Phrase> phrases = loadPhrases(phrasesFile);
        Runtime runtime = Runtime.getRuntime();
        System.gc();
        long heapBefore = runtime.totalMemory() - runtime.freeMemory();
        long loadStart = System.nanoTime();
        PinyinDictionary dictionary = loadDictionary(asset);
        long loaded = System.nanoTime();
        JavaEngine engine = new JavaEngine(name, dictionary);
        long indexed = System.nanoTime();
        System.gc();
        long heapAfter = runtime.totalMemory() - runtime.freeMemory();

        List<Outcome> outcomes = run(engine, phrases);
        Map<String, Summary> slices = summarize(outcomes);

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("asset", "\"" + asset.getName() + "\"");
        extra.put("assetBytes", asset.length());
        extra.put("keys", dictionary.size());
        extra.put("loadMs", String.format(Locale.ROOT, "%.0f", (loaded - loadStart) / 1e6));
        extra.put("keypadIndexMs", String.format(Locale.ROOT, "%.0f", (indexed - loaded) / 1e6));
        extra.put("heapMB", String.format(Locale.ROOT, "%.1f", (heapAfter - heapBefore) / 1048576.0));

        StringBuilder report = new StringBuilder();
        report.append(name).append(": ").append(extra).append('\n');
        if (latency) {
            List<Phrase> test = new ArrayList<>();
            for (Phrase p : phrases) {
                if (p.split.equals("test")) {
                    test.add(p);
                }
            }
            for (Mode mode : Mode.values()) {
                double[] t = keyLatencies(mode == Mode.T9 ? engine.keypad : engine.qwerty, test, mode, 4);
                String stats = String.format(Locale.ROOT,
                        "{\"keys\":%d,\"p50\":%.2f,\"p95\":%.2f,\"p99\":%.2f,\"max\":%.2f}",
                        t.length, percentile(t, .5), percentile(t, .95), percentile(t, .99), t[t.length - 1]);
                extra.put("latencyMs_" + mode.id, stats);
                report.append("latency ").append(mode.id).append(" (JVM, ms per key) ").append(stats).append('\n');
            }
            double[] t = pinnedLatencies(engine.keypad, test, 4);
            String stats = String.format(Locale.ROOT,
                    "{\"queries\":%d,\"p50\":%.2f,\"p95\":%.2f,\"p99\":%.2f,\"max\":%.2f}",
                    t.length, percentile(t, .5), percentile(t, .95), percentile(t, .99), t[t.length - 1]);
            extra.put("latencyMs_t9pinned", stats);
            report.append("latency t9 pinned (JVM, ms per query) ").append(stats).append('\n');
        }
        finish(name, outcomes, slices, extra, report, outDir);
        return report.toString();
    }

    /**
     * Scores an engine run as a process over the line protocol (README), such
     * as Rime's driver in bench/pinyin/rime, and writes {@code <name>.json}
     * and {@code <name>.tsv} to {@code outDir}. Returns the text report.
     */
    static String benchmarkExternal(String name, List<String> command, File phrasesFile, File outDir)
            throws IOException {
        return benchmarkExternal(name, command, phrasesFile, outDir, false);
    }

    /**
     * {@code rerank}: the keyboard's own step after Rime's ranking
     * ({@link RimeRanking}, 0.9.0) is applied to the engine's list, so the
     * benchmark scores what the keyboard shows.
     */
    static String benchmarkExternal(String name, List<String> command, File phrasesFile, File outDir, boolean rerank)
            throws IOException {
        List<Phrase> phrases = loadPhrases(phrasesFile);
        List<Outcome> outcomes;
        try (ExternalEngine engine = new ExternalEngine(name, command)) {
            outcomes = run(rerank ? new RerankedEngine(engine) : engine, phrases);
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("external", "true");
        extra.put("rerank", String.valueOf(rerank));
        StringBuilder report = new StringBuilder(name).append(": external engine\n");
        finish(name, outcomes, summarize(outcomes), extra, report, outDir);
        return report.toString();
    }

    /** Appends the slices and the weak spots to {@code report}, and writes the files when asked. */
    private static void finish(String name, List<Outcome> outcomes, Map<String, Summary> slices,
                               Map<String, Object> extra, StringBuilder report, File outDir) throws IOException {
        for (Map.Entry<String, Summary> e : slices.entrySet()) {
            report.append(String.format(Locale.ROOT, "%-22s %s%n", e.getKey(), e.getValue().line()));
        }
        for (Outcome o : outcomes) {
            if (o.phrase.tags.contains("weak")) {
                report.append(String.format(Locale.ROOT, "weak %-6s %s -> %s (rank %d, corrections %d)%n",
                        o.mode.id, o.phrase.hanzi, o.top, o.rank, o.corrections));
            }
        }
        if (outDir != null) {
            outDir.mkdirs();
            Files.write(new File(outDir, name + ".json").toPath(),
                    json(name, slices, extra).getBytes(StandardCharsets.UTF_8));
            writeRows(outcomes, new File(outDir, name + ".tsv"));
        }
    }

    /**
     * {@code bench <name> <asset> <phrases> <outDir> [latency]} scores an asset;
     * {@code external <name> <phrases> <outDir> <command...>} scores an engine run as a process;
     * {@code compare <baseline.tsv> <candidate.tsv>} prints paired deltas;
     * {@code misses <asset> <phrases> <mode> <split>} lists what went wrong.
     */
    public static void main(String[] args) throws IOException {
        PrintWriter console = new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);
        switch (args[0]) {
            case "bench":
                console.print(benchmark(args[1], new File(args[2]), new File(args[3]), new File(args[4]),
                        args.length > 5 && args[5].equals("latency")));
                break;
            case "external": {
                // external <name> <phrases> <outDir> [--rerank] <command> [<arg>...]
                boolean rerank = args[4].equals("--rerank");
                console.print(benchmarkExternal(args[1],
                        Arrays.asList(Arrays.copyOfRange(args, rerank ? 5 : 4, args.length)),
                        new File(args[2]), new File(args[3]), rerank));
                break;
            }
            case "compare":
                for (String mode : new String[]{"t9", "qwerty"}) {
                    for (String split : new String[]{"dev", "test"}) {
                        console.println(compare(new File(args[1]), new File(args[2]), mode, split));
                    }
                }
                break;
            case "tune":
                tune(console, new File(args[1]), new File(args[2]));
                break;
            case "eval": {
                // eval <asset> <phrases> <knobs> [<knobs>...]: dev summaries only.
                List<Phrase> dev = new ArrayList<>();
                for (Phrase p : loadPhrases(new File(args[2]))) {
                    if (p.split.equals("dev")) {
                        dev.add(p);
                    }
                }
                PinyinDictionary dictionary = loadDictionary(new File(args[1]));
                PinyinDigitIndex index = PinyinDigitIndex.build(dictionary);
                for (int a = 3; a < args.length; a++) {
                    String[] knobs = args[a].split(",");
                    double[] v = new double[knobs.length];
                    for (int i = 0; i < v.length; i++) {
                        v[i] = Double.parseDouble(knobs[i]);
                    }
                    Summary[] s = evaluate(dictionary, index, v, dev);
                    console.println(String.format(Locale.ROOT, "%-28s obj %.4f  t9 %s | qwerty %s",
                            args[a], objective(s[0]), s[0].line(), s[1].line()));
                }
                break;
            }
            case "misses": {
                PinyinDictionary dictionary = loadDictionary(new File(args[1]));
                JavaEngine engine;
                if (args.length > 5) {
                    String[] knobs = args[5].split(",");
                    double[] v = new double[knobs.length];
                    for (int i = 0; i < v.length; i++) {
                        v[i] = Double.parseDouble(knobs[i]);
                    }
                    engine = new JavaEngine("x", dictionary, PinyinDigitIndex.build(dictionary), scoringOf(v), (int) v[7]);
                } else {
                    engine = new JavaEngine("x", dictionary);
                }
                Mode mode = args[3].equals("t9") ? Mode.T9 : Mode.QWERTY;
                for (Phrase p : loadPhrases(new File(args[2]))) {
                    if (!args[4].equals("all") && !p.split.equals(args[4])) {
                        continue;
                    }
                    Outcome o = replay(engine, mode, p);
                    if (o.rank != 0) {
                        List<String> top = new ArrayList<>();
                        for (Cand c : engine.candidates(mode, bufferFor(mode, p, 0, 0))) {
                            if (top.size() < 6) {
                                top.add(c.text + "/" + c.consumed);
                            }
                        }
                        console.println(p.hanzi + "\t" + String.join(" ", p.syllables) + "\trank " + o.rank
                                + "\tcorr " + o.corrections + "\t" + top);
                    }
                }
                break;
            }
            default:
                throw new IllegalArgumentException(args[0]);
        }
        console.flush();
    }
}
