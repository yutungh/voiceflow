package com.voiceflowkeyboard.ime;

/** Symbol-layer labels kept as Unicode escapes so source-file decoding cannot corrupt them. */
final class KeyboardSymbols {

    private KeyboardSymbols() {
    }

    static String[] primaryTopRow() {
        return new String[]{"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"};
    }

    static String[] primaryMiddleRow() {
        return new String[]{"-", "/", ":", ";", "(", ")", "$", "&", "@", "\""};
    }

    static String[] primaryBottomRow() {
        return new String[]{".", ",", "?", "!", "'", "_", "+"};
    }

    static String[] alternateTopRow() {
        return new String[]{"[", "]", "{", "}", "#", "%", "^", "*", "+", "="};
    }

    static String[] alternateMiddleRow() {
        return new String[]{"_", "\\", "|", "~", "<", ">", "\u20ac", "\u00a3", "\u00a5", "\u2022"};
    }

    static String[] alternateBottomRow() {
        return new String[]{"`", "\u2026", "\u2014", "\u2013", "\u00bf", "\u00a1", "\u00b0"};
    }
}
