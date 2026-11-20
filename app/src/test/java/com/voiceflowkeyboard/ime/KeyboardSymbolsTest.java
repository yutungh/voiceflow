package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

public class KeyboardSymbolsTest {

    @Test
    public void alternateRowsContainTheIntendedUnicodeSymbols() {
        assertArrayEquals(
                new String[]{"_", "\\", "|", "~", "<", ">", "\u20ac", "\u00a3", "\u00a5", "\u2022"},
                KeyboardSymbols.alternateMiddleRow()
        );
        assertArrayEquals(
                new String[]{"`", "\u2026", "\u2014", "\u2013", "\u00bf", "\u00a1", "\u00b0"},
                KeyboardSymbols.alternateBottomRow()
        );
    }
}
