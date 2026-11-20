package com.voiceflowkeyboard.ime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PersonalVocabulary {
    private static final int MAX_PROMPT_CHARACTERS = 3500;

    private PersonalVocabulary() {
    }

    /**
     * Applies every correction once, to the text as it came in: a correction's
     * result is never matched again by another correction (no chains, no loops).
     * Where two corrections overlap, the one listed first wins, as before.
     */
    static String applyReplacements(String text, List<PhraseReplacement> replacements) {
        String source = text == null ? "" : text;
        if (replacements == null || replacements.isEmpty() || source.isEmpty()) {
            return source;
        }
        List<int[]> spans = new ArrayList<>();
        List<String> targets = new ArrayList<>();
        for (PhraseReplacement replacement : replacements) {
            for (String heardForm : replacement.heardForms()) {
                Matcher matcher = phrasePattern(heardForm).matcher(source);
                while (matcher.find()) {
                    int start = matcher.start();
                    int end = matcher.end();
                    if (end <= start || overlaps(spans, start, end)) {
                        continue;
                    }
                    spans.add(new int[]{start, end, targets.size()});
                    targets.add(replacement.to);
                }
            }
        }
        if (spans.isEmpty()) {
            return source;
        }
        java.util.Collections.sort(spans, (a, b) -> Integer.compare(a[0], b[0]));
        StringBuilder out = new StringBuilder(source.length() + 16);
        int cursor = 0;
        for (int[] span : spans) {
            out.append(source, cursor, span[0]).append(targets.get(span[2]));
            cursor = span[1];
        }
        return out.append(source, cursor, source.length()).toString();
    }

    private static boolean overlaps(List<int[]> spans, int start, int end) {
        for (int[] span : spans) {
            if (start < span[1] && span[0] < end) {
                return true;
            }
        }
        return false;
    }

    static ReplacementMatch findReplacementAtEnd(String text, List<PhraseReplacement> replacements) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        for (PhraseReplacement replacement : replacements) {
            for (String heardForm : replacement.heardForms()) {
                Matcher matcher = phrasePattern(heardForm).matcher(text);
                while (matcher.find()) {
                    if (matcher.end() == text.length()) {
                        return new ReplacementMatch(matcher.end() - matcher.start(), replacement.to);
                    }
                }
            }
        }
        return null;
    }

    static List<String> keywords(List<PhraseReplacement> replacements) {
        List<String> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (PhraseReplacement replacement : replacements) {
            String term = replacement.to == null ? "" : replacement.to.trim();
            String key = term.toLowerCase(Locale.US);
            if (isValidKeyword(term) && seen.add(key)) {
                result.add(term);
            }
        }
        return result;
    }

    static String transcriptionPrompt(List<PhraseReplacement> replacements) {
        Map<String, PhraseReplacement> byTerm = new LinkedHashMap<>();
        for (PhraseReplacement replacement : replacements) {
            String term = replacement.to == null ? "" : replacement.to.trim();
            if (!isValidKeyword(term)) {
                continue;
            }
            String key = term.toLowerCase(Locale.US);
            PhraseReplacement existing = byTerm.get(key);
            if (existing == null || existing.context.trim().isEmpty()) {
                byTerm.put(key, replacement);
            }
        }
        if (byTerm.isEmpty()) {
            return "";
        }

        StringBuilder prompt = new StringBuilder(
                "Personal vocabulary that may occur in this recording. "
                        + "Use these exact spellings only when supported by the audio:\n"
        );
        for (PhraseReplacement replacement : byTerm.values()) {
            String term = replacement.to.trim();
            String context = oneLine(replacement.context);
            String line = "- " + term + (context.isEmpty() ? "" : ": " + context) + "\n";
            if (prompt.length() + line.length() > MAX_PROMPT_CHARACTERS) {
                break;
            }
            prompt.append(line);
        }
        return prompt.toString().trim();
    }

    private static Pattern phrasePattern(String phrase) {
        String[] tokens = phrase.trim().split("\\s+");
        StringBuilder expression = new StringBuilder("(?i)(?<![\\p{L}\\p{N}])");
        for (int i = 0; i < tokens.length; i++) {
            if (i > 0) {
                boolean acronymLetters = isSingleLetter(tokens[i - 1]) && isSingleLetter(tokens[i]);
                expression.append(acronymLetters
                        ? "\\s*"
                        : "(?:\\s+|\\s*-\\s*)");
            }
            expression.append(Pattern.quote(tokens[i]));
            boolean acronymToken = isSingleLetter(tokens[i])
                    && ((i > 0 && isSingleLetter(tokens[i - 1]))
                    || (i + 1 < tokens.length && isSingleLetter(tokens[i + 1])));
            if (acronymToken) {
                expression.append("\\.?");
            }
        }
        expression.append("(?![\\p{L}\\p{N}])");
        return Pattern.compile(expression.toString());
    }

    private static boolean isSingleLetter(String value) {
        return value.length() == 1 && Character.isLetter(value.charAt(0));
    }

    private static boolean isValidKeyword(String value) {
        return !value.isEmpty()
                && value.indexOf('<') < 0
                && value.indexOf('>') < 0
                && value.indexOf('\r') < 0
                && value.indexOf('\n') < 0;
    }

    private static String oneLine(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
    }

    static final class ReplacementMatch {
        final int matchedLength;
        final String replacement;

        ReplacementMatch(int matchedLength, String replacement) {
            this.matchedLength = matchedLength;
            this.replacement = replacement;
        }
    }
}
