package com.voiceflowkeyboard.ime;

/** Small, pure rules shared by the Chinese layouts. */
final class ChineseInputRules {

    private ChineseInputRules() {
    }

    static String fullWidthPunctuation(String ascii) {
        switch (ascii) {
            case ".":
                return "。";
            case ",":
                return "，";
            case "?":
                return "？";
            case "!":
                return "！";
            case ":":
                return "：";
            case ";":
                return "；";
            default:
                return ascii;
        }
    }
}
