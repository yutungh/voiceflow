package com.voiceflowkeyboard.ime;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The transcription vocabulary for one dictation, built from a pinned snapshot
 * the way pack/README.md ("The vocabulary prompt") and VoiceFlow Desktop 0.9.11
 * describe it:
 * <ol>
 *   <li>words for the app being dictated into first, then words for every app by
 *       priority, within {@code promptChars} characters and {@code promptTerms}
 *       words; a word that doesn't fit is skipped and the next one tried;</li>
 *   <li>then pronunciation hints, most important word first, while they fit:
 *       the gloss when it is the person's own ({@code personal}, an edit, or this
 *       phone's list) or says how the word sounds, and the heard-as forms as
 *       {@code not "X"};</li>
 *   <li>one line, {@code Vocabulary: A, B (hint)}, most relevant last.</li>
 * </ol>
 * The words also go, without hints, as the request's {@code keywords[]}.
 * <p>0.8.5, two lists: every word chosen in step 1 goes to the tidy-up
 * ({@link Built#chosen}, {@link TidyWords}), but the transcription request (the
 * line and its {@code keywords[]}) carries only the first {@link #TRANSCRIBE_TERMS}
 * of them, in the same order (this phone's words, the app's words, then priority),
 * with hints fitted to that shorter line. On real recordings 50 or 80 words made
 * gpt-transcribe miss or invent words, and 20 got them right. VoiceFlow Desktop
 * 0.9.15 builds the same request; the shared cases are in
 * {@code pack/vectors/wordlists.json} ({@code split}).</p>
 * Written for this app; pure, no Android.
 */
final class WordPrompt {
    static final String PREFIX = "Vocabulary: ";
    static final int MAX_HINT_CHARS = 120;
    /**
     * 0.8.5: the most words the transcription request carries. A code constant,
     * not a profile field: 0.8.x refuses a profile with a field it doesn't know.
     * A later profile version may override it (pack/README.md), never past
     * {@link #TRANSCRIBE_TERMS_MAX}.
     */
    static final int TRANSCRIBE_TERMS = 20;
    /** The hard cap on the transcription request's words, whatever asks for more. */
    static final int TRANSCRIBE_TERMS_MAX = 40;

    private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]+");
    /** Never {@code (?U)}: Android's ICU engine refuses it (see {@link WhiteSpace}). */
    private static final Pattern SPACES = WhiteSpace.RUN;
    static final Pattern PRONUNCIATION = Pattern.compile(
            "\\b(said|pronounced|pronounce|sounds? like|rhymes? with|spoken as|one word|spelled)\\b"
                    + "|\"[^\"]+\"|“[^”]+”|(^|[\\s   -     　﻿])'[^'\\s][^']*'",
            Pattern.CASE_INSENSITIVE);

    private WordPrompt() {
    }

    static final class Built {
        /** The transcription request's line (at most {@link #TRANSCRIBE_TERMS} words). */
        final String prompt;
        /** Words sent to the transcriber, in prompt order (also its {@code keywords[]}). */
        final List<String> sent;
        /** Words left out of {@link #chosen} by the budget ({@code promptChars}, {@code promptTerms}). */
        final List<String> skipped;
        /**
         * 0.8.4: every word chosen within the budget, as entries, most important first
         * (the order they were chosen in), for the tidy-up's word list ({@link TidyWords}).
         * 0.8.5: the transcriber gets only the first {@link #TRANSCRIBE_TERMS}; the
         * tidy-up gets them all.
         */
        final List<WordLayer.Term> chosen;

        Built(String prompt, List<String> sent, List<String> skipped, List<WordLayer.Term> chosen) {
            this.prompt = prompt;
            this.sent = Collections.unmodifiableList(sent);
            this.skipped = Collections.unmodifiableList(skipped);
            this.chosen = Collections.unmodifiableList(chosen);
        }
    }

    /** A word as it goes on the one-line prompt: control and format characters become spaces. */
    static int transcribeTermsLimit(int requested) {
        return Math.max(1, Math.min(TRANSCRIBE_TERMS_MAX, requested));
    }

    static String promptWord(String text) {
        String cleaned = CONTROL.matcher(text == null ? "" : text).replaceAll(" ");
        return SPACES.matcher(cleaned).replaceAll(" ").trim();
    }

    private static String hintText(String text) {
        return promptWord(text).replace("(", "").replace(")", "").replace('"', '\'').trim();
    }

    static boolean ownLayer(String layer) {
        return WordLayer.PERSONAL.equals(layer) || WordLayer.EDIT.equals(layer) || WordLayer.PHONE.equals(layer);
    }

    /** The hint in brackets after a word, or null. */
    static String termHint(WordLayer.Term term) {
        String glossText = term.gloss.isEmpty() ? "" : hintText(term.gloss);
        if (glossText.length() > 80) {
            glossText = glossText.substring(0, 80);
        }
        String gloss = !glossText.isEmpty() && (ownLayer(term.layer) || PRONUNCIATION.matcher(term.gloss).find())
                ? glossText : "";
        String word = norm(term.text);
        Set<String> heard = new LinkedHashSet<>();
        for (String form : term.heardAs) {
            String h = hintText(form);
            if (!h.isEmpty() && !norm(h).equals(word)) {
                heard.add(h);
            }
        }
        String hint = gloss;
        String forms = "";
        for (String form : heard) {
            String nextForms = forms.isEmpty() ? "not \"" + form + "\"" : forms + " or \"" + form + "\"";
            String next = gloss.isEmpty() ? nextForms : gloss + "; " + nextForms;
            if (next.length() > MAX_HINT_CHARS) {
                break;
            }
            forms = nextForms;
            hint = next;
        }
        return hint.isEmpty() ? null : hint;
    }

    static Built build(WordMerge.Snapshot snapshot, String appId) {
        return build(snapshot.phoneTerms, snapshot.terms, snapshot.promptChars(), snapshot.promptTerms(), appId);
    }

    /** {@code terms}: merged words; those from this phone's layer go first. */
    static Built build(List<WordLayer.Term> terms, int maxChars, int maxTerms, String appId) {
        List<WordLayer.Term> phone = new ArrayList<>();
        for (WordLayer.Term term : terms) {
            if (WordLayer.PHONE.equals(term.layer)) {
                phone.add(term);
            }
        }
        return build(phone, terms, maxChars, maxTerms, appId);
    }

    /**
     * {@code phoneTerms}: this phone's own words, selected first in their order
     * whatever their app or a duplicate elsewhere; {@code terms}: the merged words.
     */
    static Built build(List<WordLayer.Term> phoneTerms, List<WordLayer.Term> terms, int maxChars, int maxTerms,
                       String appId) {
        return build(phoneTerms, terms, maxChars, maxTerms, appId, TRANSCRIBE_TERMS);
    }

    /** {@code transcribeTerms}: the request's word count, kept within 1..{@link #TRANSCRIBE_TERMS_MAX}. */
    static Built build(List<WordLayer.Term> phoneTerms, List<WordLayer.Term> terms, int maxChars, int maxTerms,
                       String appId, int transcribeTerms) {
        String app = appId == null || appId.isEmpty() ? null : appId.toLowerCase(Locale.ROOT);
        Collator collator = Collator.getInstance(Locale.ENGLISH);
        collator.setStrength(Collator.PRIMARY);
        Comparator<WordLayer.Term> byPriority = (a, b) -> {
            int p = Integer.compare(b.priority, a.priority);
            return p != 0 ? p : collator.compare(a.text, b.text);
        };
        // This phone's own rows come first, in their order: they were always sent
        // before, and no priority from the sync server may push them out.
        List<WordLayer.Term> forPhone = new ArrayList<>(phoneTerms);
        List<WordLayer.Term> forApp = new ArrayList<>();
        List<WordLayer.Term> forAll = new ArrayList<>();
        for (WordLayer.Term term : terms) {
            if (WordLayer.PHONE.equals(term.layer)) {
                continue; // already first, from phoneTerms
            } else if (term.apps.contains(WordLayer.EVERY_APP)) {
                forAll.add(term);
            } else if (app != null && term.apps.contains(app)) {
                forApp.add(term);
            }
        }
        forApp.sort(byPriority);
        forAll.sort(byPriority);

        // 1. Select, within the whole budget: the tidy-up's list. Each entry is
        // {text, term, pass}: pass 0 this phone's words, 1 the app's, 2 every app's.
        Set<String> seen = new HashSet<>();
        List<String> skipped = new ArrayList<>();
        List<Object[]> chosen = new ArrayList<>();
        int[] used = {PREFIX.length()};
        int[] count = {0};
        for (int pass = 0; pass < 3; pass++) {
            List<WordLayer.Term> source = pass == 0 ? forPhone : pass == 1 ? forApp : forAll;
            for (WordLayer.Term term : source) {
                String text = promptWord(term.text);
                String key = norm(text);
                if (text.isEmpty() || seen.contains(key)) {
                    continue;
                }
                int cost = text.length() + (count[0] > 0 ? 2 : 0);
                if (count[0] >= maxTerms || used[0] + cost > maxChars) {
                    skipped.add(text);
                    continue;
                }
                seen.add(key);
                chosen.add(new Object[]{text, term, pass});
                used[0] += cost;
                count[0]++;
            }
        }
        List<WordLayer.Term> chosenTerms = new ArrayList<>();
        for (Object[] entry : chosen) {
            chosenTerms.add((WordLayer.Term) entry[1]);
        }
        // 2. The transcription request: the first words chosen, plain, so hints never
        // push a word out. Selection is cumulative, so these are exactly the words a
        // budget of this many words would have chosen.
        List<Object[]> request = chosen.subList(0, Math.min(chosen.size(), transcribeTermsLimit(transcribeTerms)));
        int line = PREFIX.length();
        for (int i = 0; i < request.size(); i++) {
            line += ((String) request.get(i)[0]).length() + (i > 0 ? 2 : 0);
        }
        // 3. Then hints, most important word first, while they fit.
        Map<String, String> entries = new LinkedHashMap<>();
        for (Object[] entry : request) {
            String hint = termHint((WordLayer.Term) entry[1]);
            if (hint == null) {
                continue;
            }
            int extra = hint.length() + 3;
            if (line + extra > maxChars) {
                continue;
            }
            entries.put((String) entry[0], entry[0] + " (" + hint + ")");
            line += extra;
        }
        // Most relevant last: every app's words, the app's words, this phone's words.
        List<String> sent = new ArrayList<>();
        for (int pass = 2; pass >= 0; pass--) {
            for (int i = request.size() - 1; i >= 0; i--) {
                if ((Integer) request.get(i)[2] == pass) {
                    sent.add((String) request.get(i)[0]);
                }
            }
        }
        if (sent.isEmpty()) {
            return new Built("", sent, skipped, chosenTerms);
        }
        StringBuilder prompt = new StringBuilder(PREFIX);
        for (int i = 0; i < sent.size(); i++) {
            if (i > 0) {
                prompt.append(", ");
            }
            String word = sent.get(i);
            prompt.append(entries.containsKey(word) ? entries.get(word) : word);
        }
        return new Built(prompt.toString(), sent, skipped, chosenTerms);
    }

    /** The request's {@code keywords[]}: the sent words, each once, without hints. */
    static List<String> keywords(List<String> sent) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String raw : sent) {
            String word = raw.trim();
            String key = word.toLowerCase(Locale.ROOT);
            if (word.isEmpty() || !seen.add(key) || word.indexOf('<') >= 0 || word.indexOf('>') >= 0
                    || word.indexOf('\r') >= 0 || word.indexOf('\n') >= 0) {
                continue;
            }
            out.add(word);
        }
        return out;
    }

    private static String norm(String value) {
        return SPACES.matcher(value.trim()).replaceAll(" ").toLowerCase(Locale.ROOT);
    }
}
