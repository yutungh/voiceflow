package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ChineseInputRulesTest {

    @Test
    public void asciiPunctuationMapsToRealChineseCodePoints() {
        assertEquals("。", ChineseInputRules.fullWidthPunctuation("."));
        assertEquals("，", ChineseInputRules.fullWidthPunctuation(","));
        assertEquals("？", ChineseInputRules.fullWidthPunctuation("?"));
        assertEquals("！", ChineseInputRules.fullWidthPunctuation("!"));
        assertEquals("：", ChineseInputRules.fullWidthPunctuation(":"));
        assertEquals("；", ChineseInputRules.fullWidthPunctuation(";"));
        assertEquals(" ", ChineseInputRules.fullWidthPunctuation(" "));
    }
}
