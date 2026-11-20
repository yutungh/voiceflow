package com.voiceflowkeyboard.ime;

import java.util.ArrayList;
import java.util.List;

/**
 * Rejects a supposed spelling-only edit if it changes the document's shape or
 * makes word substitutions too large to plausibly be typo corrections.
 */
final class SpellingOnlyGuard {

    private SpellingOnlyGuard() {
    }

    static String requireSafe(String source, String corrected) {
        String original = source == null ? "" : source;
        // Transform providers trim their plain-text response. Restore the
        // editor's exact outer whitespace before validating and applying it.
        String candidate = restoreBoundaryWhitespace(original, corrected == null ? "" : corrected);
        ParsedText before = ParsedText.parse(original);
        ParsedText after = ParsedText.parse(candidate);

        if (!before.separators.equals(after.separators)
                || before.words.size() != after.words.size()) {
            throw new IllegalArgumentException("Spelling result changed formatting or punctuation; original kept.");
        }
        for (int i = 0; i < before.words.size(); i++) {
            String oldWord = before.words.get(i);
            String newWord = after.words.get(i);
            if (!sameCaseStyle(oldWord, newWord)
                    || !isPlausibleCorrection(oldWord, newWord)) {
                throw new IllegalArgumentException("Spelling result changed wording; original kept.");
            }
        }
        return candidate;
    }

    private static String restoreBoundaryWhitespace(String source, String corrected) {
        int sourceStart = 0;
        while (sourceStart < source.length() && Character.isWhitespace(source.charAt(sourceStart))) {
            sourceStart++;
        }
        int sourceEnd = source.length();
        while (sourceEnd > sourceStart && Character.isWhitespace(source.charAt(sourceEnd - 1))) {
            sourceEnd--;
        }
        int correctedStart = 0;
        while (correctedStart < corrected.length()
                && Character.isWhitespace(corrected.charAt(correctedStart))) {
            correctedStart++;
        }
        int correctedEnd = corrected.length();
        while (correctedEnd > correctedStart
                && Character.isWhitespace(corrected.charAt(correctedEnd - 1))) {
            correctedEnd--;
        }
        return source.substring(0, sourceStart)
                + corrected.substring(correctedStart, correctedEnd)
                + source.substring(sourceEnd);
    }

    private static boolean isPlausibleCorrection(String oldWord, String newWord) {
        if (oldWord.equals(newWord)) {
            return true;
        }
        if (!isAsciiWordWithJoiners(oldWord) || !isAsciiWordWithJoiners(newWord)) {
            return false;
        }
        String oldLetters = withoutJoiners(oldWord);
        String newLetters = withoutJoiners(newWord);
        int longest = Math.max(oldLetters.length(), newLetters.length());
        int allowed = longest >= 7 ? 2 : 1;
        return damerauLevenshtein(oldLetters, newLetters) <= allowed;
    }

    private static boolean isAsciiWordWithJoiners(String word) {
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (!isAsciiLetter(c) && !isWordJoiner(c)) {
                return false;
            }
        }
        return !word.isEmpty()
                && isAsciiLetter(word.charAt(0))
                && isAsciiLetter(word.charAt(word.length() - 1));
    }

    private static String withoutJoiners(String word) {
        StringBuilder letters = new StringBuilder(word.length());
        for (int i = 0; i < word.length(); i++) {
            if (!isWordJoiner(word.charAt(i))) {
                letters.append(word.charAt(i));
            }
        }
        return letters.toString();
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isWordJoiner(char c) {
        return c == '\'' || c == '\u2019' || c == '-';
    }

    private static boolean isInternalWordJoiner(String text, int index) {
        return index > 0
                && index + 1 < text.length()
                && isWordJoiner(text.charAt(index))
                && isAsciiLetter(text.charAt(index - 1))
                && isAsciiLetter(text.charAt(index + 1));
    }

    private static boolean sameCaseStyle(String left, String right) {
        return caseStyle(left) == caseStyle(right);
    }

    private static int caseStyle(String word) {
        boolean upper = true;
        boolean lower = true;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            upper &= !Character.isLetter(c) || Character.isUpperCase(c);
            lower &= !Character.isLetter(c) || Character.isLowerCase(c);
        }
        if (upper) {
            return 1;
        }
        if (lower) {
            return 2;
        }
        boolean title = Character.isUpperCase(word.charAt(0));
        for (int i = 1; i < word.length() && title; i++) {
            title = !Character.isLetter(word.charAt(i)) || Character.isLowerCase(word.charAt(i));
        }
        return title ? 3 : 4;
    }

    /** Adjacent transposition counts as one edit, so "teh" -> "the" is valid. */
    private static int damerauLevenshtein(String left, String right) {
        String a = left.toLowerCase(java.util.Locale.US);
        String b = right.toLowerCase(java.util.Locale.US);
        int[][] distance = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            distance[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            distance[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int substitution = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                distance[i][j] = Math.min(
                        Math.min(distance[i - 1][j] + 1, distance[i][j - 1] + 1),
                        distance[i - 1][j - 1] + substitution
                );
                if (i > 1 && j > 1
                        && a.charAt(i - 1) == b.charAt(j - 2)
                        && a.charAt(i - 2) == b.charAt(j - 1)) {
                    distance[i][j] = Math.min(distance[i][j], distance[i - 2][j - 2] + 1);
                }
            }
        }
        return distance[a.length()][b.length()];
    }

    private static final class ParsedText {
        final List<String> words;
        final List<String> separators;

        ParsedText(List<String> words, List<String> separators) {
            this.words = words;
            this.separators = separators;
        }

        static ParsedText parse(String text) {
            List<String> words = new ArrayList<>();
            List<String> separators = new ArrayList<>();
            int cursor = 0;
            while (cursor < text.length()) {
                int separatorStart = cursor;
                while (cursor < text.length() && !Character.isLetter(text.charAt(cursor))) {
                    cursor++;
                }
                separators.add(text.substring(separatorStart, cursor));
                if (cursor >= text.length()) {
                    break;
                }
                int wordStart = cursor;
                while (cursor < text.length()) {
                    char c = text.charAt(cursor);
                    if (Character.isLetter(c) || isInternalWordJoiner(text, cursor)) {
                        cursor++;
                    } else {
                        break;
                    }
                }
                words.add(text.substring(wordStart, cursor));
            }
            if (separators.size() == words.size()) {
                separators.add("");
            }
            return new ParsedText(words, separators);
        }
    }
}
