package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PrefsLanguageContractTest {

    @Test
    public void chineseDictationDefaultsToSimplifiedUnlessExplicitlyRequestedOtherwise() {
        String contract = Prefs.languageContract();

        assertTrue(contract.contains("Chinese speech produces Simplified Chinese text by default"));
        assertTrue(contract.contains("use Traditional Chinese only when the speaker explicitly requests it"));
    }
}
