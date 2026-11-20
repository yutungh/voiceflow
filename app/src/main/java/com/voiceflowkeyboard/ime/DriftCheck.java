package com.voiceflowkeyboard.ime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spots a tidy-up that changed the language of part of what was said: a clause
 * of mixed Chinese and English speech that came back translated.
 *
 * <p>It is a suspicion detector, not a guarantee. A tidy-up is allowed to delete
 * (fillers in either language, false starts, the abandoned half of a
 * self-correction), so a missing word alone never counts. What counts is a word
 * or character that the speaker never said appearing in the other script:
 * "因为" turning into "because", "我们可以" into "we can". The thresholds were
 * set on clips that include those legitimate deletions; see DriftCheckTest.
 *
 * <p>Compares the transcript as captured, before any corrections are applied.
 */
final class DriftCheck {
    static final String REASON_NONE = "";
    /** Most of one script the speaker used is gone ({@link DictationText#translatedAway}). */
    static final String REASON_TRANSLATED = "translated";
    /** Chinese the speaker did not say. */
    static final String REASON_HAN_ADDED = "han-added";
    /** English the speaker did not say, in speech that has Chinese. */
    static final String REASON_LATIN_ADDED = "latin-added";
    /** One word swapped for the other script ("because" for "因为"). */
    static final String REASON_SWAPPED = "swapped";

    /** Added characters or words in the other script at which the result counts as drifted. */
    static final int ADDED_MIN = 2;

    private static final Pattern LATIN_WORD = Pattern.compile("[A-Za-z][A-Za-z'’-]*");

    /** Spoken English that a tidy-up may delete on its own. */
    private static final String[] LATIN_FILLERS = {
            "um", "umm", "uh", "uhh", "er", "erm", "hmm", "mm", "like", "you", "know", "i", "mean",
            "so", "basically", "actually", "literally", "okay", "ok", "anyway", "yeah", "oh", "and",
            "also", "well", "right", "just", "side", "note", "no", "wait", "sorry", "then", "the", "a"
    };

    /** Chinese hesitation words and correction markers a tidy-up may delete. */
    private static final String[] HAN_FILLERS = {"那个", "这个", "就是", "嗯", "呃", "额", "啊", "哦", "不对", "然后"};

    static final class Result {
        final boolean drift;
        final String reason;
        /** The transcript holds both Chinese and English. */
        final boolean mixed;

        Result(boolean drift, String reason, boolean mixed) {
            this.drift = drift;
            this.reason = reason;
            this.mixed = mixed;
        }
    }

    private DriftCheck() {
    }

    static boolean isMixed(String source) {
        return DictationText.countHan(source) > 0 && DictationText.countLatinWords(source) > 0;
    }

    static Result check(String source, String output) {
        return check(source, output, Collections.<TidyWords.Word>emptyList());
    }

    /**
     * 0.8.4: {@code words} (the tidy-up's word list) earns a listed word written in place
     * of one of its known mishearings a credit ({@link #credit}); whether the speech was
     * mixed is still judged on the transcript as it was.
     */
    static Result check(String source, String output, List<TidyWords.Word> words) {
        String original = source == null ? "" : source;
        boolean sourceHasHan = DictationText.countHan(original) > 0;
        boolean mixed = isMixed(original);
        String[] credited = credit(original, output == null ? "" : output,
                words == null ? Collections.<TidyWords.Word>emptyList() : words);
        return checkCredited(original, credited[0], credited[1], sourceHasHan, mixed);
    }

    // ---------------------------------------------------------------- credits for the word list

    /** A listed word ({@code form} false) or one of its heard-as forms, found in a text. */
    private static final class Hit {
        final int word;
        final boolean form;
        final int start;
        final int end;

        Hit(int word, boolean form, int start, int end) {
            this.word = word;
            this.form = form;
            this.start = start;
            this.end = end;
        }
    }

    private static boolean isWordChar(String text, int at) {
        if (at < 0 || at >= text.length()) {
            return false;
        }
        char c = text.charAt(at);
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }

    /** Upper-case ASCII letters lowered; the length never changes. */
    private static String foldAscii(String text) {
        char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            if (chars[i] >= 'A' && chars[i] <= 'Z') {
                chars[i] = (char) (chars[i] + ('a' - 'A'));
            }
        }
        return new String(chars);
    }

    /** What can continue an English word as the guard counts words ({@code LATIN_WORD}), and digits. */
    private static boolean isTokenChar(String text, int at) {
        if (isWordChar(text, at)) {
            return true;
        }
        if (at < 0 || at >= text.length()) {
            return false;
        }
        char c = text.charAt(at);
        return c == '\'' || c == '’' || c == '-';
    }

    /**
     * Where {@code needle} occurs in {@code text}: ASCII letters in any case, a whole word
     * where it starts or ends with a Latin letter or digit (so never part of a longer word
     * such as "ZDRL-ready"), never overlapping {@code taken}.
     */
    private static List<int[]> occurrences(String text, String needle, List<int[]> taken) {
        List<int[]> found = new ArrayList<>();
        String n = foldAscii(needle.trim());
        if (n.isEmpty()) {
            return found;
        }
        String t = foldAscii(text);
        boolean edgeStart = isWordChar(n, 0);
        boolean edgeEnd = isWordChar(n, n.length() - 1);
        int from = 0;
        while (from <= t.length() - n.length()) {
            int at = t.indexOf(n, from);
            if (at < 0) {
                break;
            }
            int end = at + n.length();
            boolean whole = (!edgeStart || !isTokenChar(t, at - 1)) && (!edgeEnd || !isTokenChar(t, end));
            boolean free = true;
            for (List<int[]> spans : Arrays.asList(taken, found)) {
                for (int[] span : spans) {
                    if (at < span[1] && span[0] < end) {
                        free = false;
                    }
                }
            }
            if (whole && free) {
                found.add(new int[]{at, end});
                from = end;
            } else {
                from = at + 1;
            }
        }
        return found;
    }

    /**
     * Every listed word and heard-as form in {@code text}, found in the text as it is: the
     * longest first, no two overlapping. On a tie (a heard form that is also another listed
     * word's spelling), the transcript gives the span to a heard form ({@code formsFirst}:
     * what was misheard) and the tidy-up to a spelling (what was written); then list order.
     * Sorted by position.
     */
    private static List<Hit> hits(String text, List<TidyWords.Word> words, boolean formsFirst) {
        List<Object[]> needles = new ArrayList<>(); // {word index, form?, text}
        for (int i = 0; i < words.size(); i++) {
            TidyWords.Word word = words.get(i);
            String spelling = word.text.trim();
            needles.add(new Object[]{i, false, spelling});
            for (String raw : word.heardAs) {
                String form = raw == null ? "" : raw.trim();
                if (!form.isEmpty() && !foldAscii(form).equals(foldAscii(spelling))) {
                    needles.add(new Object[]{i, true, form});
                }
            }
        }
        List<Object[]> order = new ArrayList<>(needles);
        // Stable: the remaining ties keep list order.
        order.sort((a, b) -> {
            int byLength = Integer.compare(((String) b[2]).length(), ((String) a[2]).length());
            if (byLength != 0) {
                return byLength;
            }
            boolean formA = (Boolean) a[1];
            boolean formB = (Boolean) b[1];
            return formA == formB ? 0 : formA == formsFirst ? -1 : 1;
        });
        List<int[]> taken = new ArrayList<>();
        List<Hit> found = new ArrayList<>();
        for (Object[] needle : order) {
            for (int[] span : occurrences(text, (String) needle[2], taken)) {
                taken.add(span);
                found.add(new Hit((Integer) needle[0], (Boolean) needle[1], span[0], span[1]));
            }
        }
        found.sort((a, b) -> Integer.compare(a.start, b.start));
        return found;
    }

    /**
     * A listed word the tidy-up wrote in place of one of its known mishearings is a
     * spelling fix, not drift. For each listed word the credit is what actually changed:
     * no more than the heard-as forms the tidy-up removed and no more than the listed
     * word's occurrences it added. That many forms (in the transcript) and occurrences of
     * the word (in the tidy-up) are taken out, each replaced by a space. A listed word
     * with no known form removed earns no credit and is checked as before. Matches are
     * found on the texts as they are, never on a text already masked. Returns
     * {source, output}. The same rule as VoiceFlow Desktop 0.9.14 ({@code drift-check.ts}).
     */
    static String[] credit(String source, String output, List<TidyWords.Word> words) {
        if (words.isEmpty()) {
            return new String[]{source, output};
        }
        List<Hit> inSource = hits(source, words, true);
        List<Hit> inOutput = hits(output, words, false);
        List<int[]> maskSource = new ArrayList<>();
        List<int[]> maskOutput = new ArrayList<>();
        for (int i = 0; i < words.size(); i++) {
            List<Hit> formsIn = select(inSource, i, true);
            List<Hit> formsOut = select(inOutput, i, true);
            List<Hit> wordIn = select(inSource, i, false);
            List<Hit> wordOut = select(inOutput, i, false);
            int credit = Math.min(
                    Math.max(0, formsIn.size() - formsOut.size()),
                    Math.max(0, wordOut.size() - wordIn.size()));
            for (int k = 0; k < credit; k++) {
                maskSource.add(new int[]{formsIn.get(k).start, formsIn.get(k).end});
                maskOutput.add(new int[]{wordOut.get(k).start, wordOut.get(k).end});
            }
        }
        return new String[]{blank(source, maskSource), blank(output, maskOutput)};
    }

    private static List<Hit> select(List<Hit> hits, int word, boolean form) {
        List<Hit> out = new ArrayList<>();
        for (Hit hit : hits) {
            if (hit.word == word && hit.form == form) {
                out.add(hit);
            }
        }
        return out;
    }

    /** {@code text} with each span replaced by one space. */
    private static String blank(String text, List<int[]> spans) {
        List<int[]> sorted = new ArrayList<>(spans);
        sorted.sort((a, b) -> Integer.compare(b[0], a[0]));
        String out = text;
        for (int[] span : sorted) {
            out = out.substring(0, span[0]) + " " + out.substring(span[1]);
        }
        return out;
    }

    private static Result checkCredited(String original, String src, String out, boolean sourceHasHan,
                                       boolean mixed) {
        if (DictationText.translatedAway(original, src, out)) {
            return new Result(true, REASON_TRANSLATED, mixed);
        }
        // Chinese added to speech that was English or mixed. Pure Chinese speech is
        // edited freely in Chinese, so added characters there mean nothing.
        int hanAdded = 0;
        if (mixed || !sourceHasHan) {
            hanAdded = added(hanCounts(src), hanCounts(out));
            if (hanAdded >= ADDED_MIN) {
                return new Result(true, REASON_HAN_ADDED, mixed);
            }
        }
        if (!sourceHasHan) {
            return new Result(false, REASON_NONE, false);
        }
        // English added to speech that has Chinese.
        int latinAdded = added(latinCounts(src), latinCounts(out));
        if (latinAdded >= ADDED_MIN) {
            return new Result(true, REASON_LATIN_ADDED, mixed);
        }
        // One word swapped across scripts: something added on one side while a real
        // (non-filler) word went missing on the other.
        if (latinAdded >= 1 && missingContentHan(src, out) >= 1) {
            return new Result(true, REASON_SWAPPED, mixed);
        }
        if (hanAdded >= 1 && missingContentLatin(src, out) >= 1) {
            return new Result(true, REASON_SWAPPED, mixed);
        }
        return new Result(false, REASON_NONE, mixed);
    }

    /** How many items {@code out} holds beyond what {@code src} holds. */
    private static int added(Map<String, Integer> src, Map<String, Integer> out) {
        int extra = 0;
        for (Map.Entry<String, Integer> entry : out.entrySet()) {
            Integer had = src.get(entry.getKey());
            extra += Math.max(0, entry.getValue() - (had == null ? 0 : had));
        }
        return extra;
    }

    private static int missingContentHan(String src, String out) {
        Map<String, Integer> source = hanCounts(stripHanFillers(src));
        Map<String, Integer> output = hanCounts(out);
        return added(output, source);
    }

    private static int missingContentLatin(String src, String out) {
        Map<String, Integer> source = latinCounts(src);
        for (String filler : LATIN_FILLERS) {
            source.remove(filler);
        }
        return added(latinCounts(out), source);
    }

    private static String stripHanFillers(String text) {
        String result = text;
        for (String filler : HAN_FILLERS) {
            result = result.replace(filler, "");
        }
        return result;
    }

    private static Map<String, Integer> hanCounts(String text) {
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) {
                counts.merge(new String(Character.toChars(cp)), 1, Integer::sum);
            }
            i += Character.charCount(cp);
        }
        return counts;
    }

    private static Map<String, Integer> latinCounts(String text) {
        Map<String, Integer> counts = new HashMap<>();
        Matcher matcher = LATIN_WORD.matcher(text);
        while (matcher.find()) {
            counts.merge(normalizeWord(matcher.group()), 1, Integer::sum);
        }
        return counts;
    }

    private static String normalizeWord(String word) {
        String w = word.toLowerCase(Locale.ROOT).replace('’', '\'');
        while (w.endsWith("'") || w.endsWith("-")) {
            w = w.substring(0, w.length() - 1);
        }
        return "ok".equals(w) ? "okay" : w;
    }
}
