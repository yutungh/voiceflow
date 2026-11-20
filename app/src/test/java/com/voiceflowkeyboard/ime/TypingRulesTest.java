package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Phase 5 gate: the two typographic rules, and — more importantly — every
 * position where they must decline to fire.
 */
public class TypingRulesTest {

    /** Applies an outcome to a string, the way the service applies it to the editor. */
    private static String apply(String before, String separator) {
        TypingRules.Outcome outcome = TypingRules.forSeparator(separator, before);
        if (!outcome.applies()) {
            return before + separator;
        }
        return before.substring(0, before.length() - outcome.deleteBefore) + outcome.insert;
    }

    // -------------------------------------------------- double space to stop

    @Test
    public void doubleSpaceBecomesAFullStop() {
        assertEquals("hello. ", apply("hello ", " "));
        assertEquals("I said hello. ", apply("I said hello ", " "));
        assertEquals("it's. ", apply("it's ", " "));
        assertEquals("chapter 12. ", apply("chapter 12 ", " "));
    }

    @Test
    public void aThirdSpaceDoesNotStack() {
        // "hello. " already ends space-preceded-by-space, so the rule declines
        // and the run stops rather than producing "hello.. ".
        assertEquals("hello.  ", apply("hello. ", " "));
        assertEquals("hello   ", apply("hello  ", " "));
    }

    @Test
    public void spaceAfterPunctuationIsLeftAlone() {
        assertEquals("wait, ", apply("wait,", " "));
        assertEquals("really? ", apply("really?", " "));
        assertEquals("end. ", apply("end.", " "));
    }

    @Test
    public void ruleCannotFireAtTheStartOfAField() {
        assertEquals(" ", apply("", " "));
        assertEquals("  ", apply(" ", " "));
    }

    // ---------------------------------------------- space before punctuation

    @Test
    public void spaceBeforeClosingPunctuationIsDropped() {
        assertEquals("hello.", apply("hello ", "."));
        assertEquals("hello,", apply("hello ", ","));
        assertEquals("hello?", apply("hello ", "?"));
        assertEquals("hello!", apply("hello ", "!"));
    }

    // ------------------------------------- colon and semicolon, deferred

    @Test
    public void colonAndSemicolonKeepTheSpaceWhenPressed() {
        // Either could be opening ":)" or ";)", so nothing is decided yet.
        assertEquals("hello :", apply("hello ", ":"));
        assertEquals("hello ;", apply("hello ", ";"));
    }

    @Test
    public void aSpaceAfterTheMarkClosesTheGap() {
        assertEquals("Note: ", apply("Note :", " "));
        assertEquals("first; ", apply("first ;", " "));
        assertEquals("Note: ", type("Note ", ":", " "));
    }

    @Test
    public void textEmoticonsSurvive() {
        assertEquals("Hey :| ", type("Hey ", ":", "|", " "));
        assertEquals("ok :) ", type("ok ", ":", ")", " "));
        assertEquals("ok :( ", type("ok ", ":", "(", " "));
        assertEquals("lol :D ", type("lol ", ":", "D", " "));
        assertEquals("sure :P ", type("sure ", ":", "P", " "));
        assertEquals("nice ;) ", type("nice ", ";", ")", " "));
        assertEquals("hi :-) ", type("hi ", ":", "-", ")", " "));
        assertEquals("oh :/ ", type("oh ", ":", "/", " "));
    }

    @Test
    public void unspacedColonsAreUntouched() {
        assertEquals("at 3:30 ", type("at 3", ":", "3", "0", " "));
        assertEquals("Note: ", type("Note", ":", " "));
        assertEquals("oh no D: ", type("oh no D", ":", " "));
    }

    @Test
    public void theDeferredCaseNeedsAWordBeforeTheSpace() {
        // Two spaces read as deliberate, and a bare mark at the start is left alone.
        assertEquals("hello  : ", apply("hello  :", " "));
        assertEquals(" : ", apply(" :", " "));
        assertEquals(": ", apply(":", " "));
    }

    @Test
    public void theDeferredCaseDoesNotStackWithDoubleSpace() {
        assertEquals("Note:  ", type("Note ", ":", " ", " "));
    }

    @Test
    public void punctuationDirectlyAfterAWordIsUntouched() {
        assertEquals("hello.", apply("hello", "."));
        assertEquals("hello,", apply("hello", ","));
    }

    @Test
    public void punctuationAfterTwoSpacesIsUntouched() {
        // Two spaces reads as deliberate; only a single space gets absorbed.
        assertEquals("hello  .", apply("hello  ", "."));
    }

    @Test
    public void otherKeysAreNeverRewritten() {
        assertFalse(TypingRules.forSeparator("a", "hello ").applies());
        assertFalse(TypingRules.forSeparator("-", "hello ").applies());
        assertFalse(TypingRules.forSeparator("'", "hello ").applies());
        assertFalse(TypingRules.forSeparator("", "hello ").applies());
    }

    @Test
    public void nullsAreSafe() {
        assertFalse(TypingRules.forSeparator(null, "hello ").applies());
        assertFalse(TypingRules.forSeparator(" ", null).applies());
    }

    /**
     * The service only reads three characters of context, so the rules must
     * reach their verdict from exactly that much.
     */
    @Test
    public void threeCharactersOfContextAreEnough() {
        assertEquals("o. ", apply("o ", " "));
        assertEquals("o.", apply("o ", "."));
        assertEquals("o: ", apply("o :", " "));
        assertFalse(TypingRules.forSeparator(" ", ". ").applies());
        assertFalse(TypingRules.forSeparator(" ", "  ").applies());
    }

    /**
     * An immediate rewrite removes exactly the one space it is absorbing. If
     * this ever grew, the service's fixed lookbehind would no longer be enough
     * to justify the deletion.
     */
    @Test
    public void anImmediateRewriteOnlyConsumesTheSingleTrailingSpace() {
        String[][] firing = {
                {"hello ", " "}, {"hello ", "."}, {"hello ", ","},
                {"hello ", "?"}, {"hello ", "!"},
        };
        for (String[] row : firing) {
            TypingRules.Outcome outcome = TypingRules.forSeparator(row[1], row[0]);
            assertTrue(row[0] + " + " + row[1] + " should rewrite", outcome.applies());
            assertEquals(1, outcome.deleteBefore);
        }
    }

    /** The deferred rewrite removes the space and the mark, and puts the mark back. */
    @Test
    public void theDeferredRewriteConsumesOnlyTheSpaceAndTheMark() {
        for (String mark : new String[]{":", ";"}) {
            TypingRules.Outcome outcome = TypingRules.forSeparator(" ", "hello " + mark);
            assertTrue(outcome.applies());
            assertEquals(2, outcome.deleteBefore);
            assertEquals(mark + " ", outcome.insert);
        }
    }

    /**
     * Types keys one at a time the way the service does: separator keys go
     * through the rules with three characters of context, and every other key
     * is committed as-is.
     */
    private static String type(String start, String... keys) {
        String text = start;
        for (String key : keys) {
            if (" .,?!:;".contains(key)) {
                String context = text.substring(Math.max(0, text.length() - 3));
                String applied = apply(context, key);
                text = text.substring(0, text.length() - context.length()) + applied;
            } else {
                text = text + key;
            }
        }
        return text;
    }
}
