package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** What the voice-style transform is told, against real SharedPreferences. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class PrefsPromptTest {
    private static final String PARTNER_LABEL = "Sunny";
    private static final String CUSTOM_LABEL = "Kite crew";

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.shared(context).edit().clear().commit();
        Prefs.addVoiceStyleTemplate(context, Prefs.PRESET_PARTNER);
        Prefs.savePromptProfile(
                context,
                Prefs.PRESET_PARTNER,
                PARTNER_LABEL,
                "",
                Prefs.defaultPromptForPreset(Prefs.PRESET_PARTNER),
                Prefs.styleGuidanceForPreset(context, Prefs.PRESET_PARTNER)
        );
        Prefs.addPromptProfile(context, CUSTOM_LABEL);
    }

    @Test
    public void workPromptOpensAndClosesWithTheLanguageRule() {
        String prompt = Prefs.promptForPreset(context, Prefs.PRESET_BUSINESS, Prefs.EXPRESSION_LIVELY);
        assertTrue(prompt.startsWith(Prefs.languageRuleFirst()));
        assertTrue(prompt.contains("This applies to every style, including Work."));
        assertTrue(prompt.contains("put one space between an English word and a Chinese character"));
    }

    @Test
    public void funStylesGetNoLanguageRule() {
        String prompt = Prefs.promptForPreset(context, Prefs.PRESET_FUN_HAIKU, Prefs.EXPRESSION_NATURAL);
        assertFalse(prompt.contains(Prefs.languageRuleFirst()));
    }

    @Test
    public void styleNamesNeverReachTheDictationPrompt() {
        for (String preset : Prefs.selectablePresetValues(context)) {
            String prompt = Prefs.promptForPreset(context, preset, Prefs.EXPRESSION_LIVELY);
            assertFalse(preset + " prompt names the partner style", prompt.contains(PARTNER_LABEL));
            assertFalse(preset + " prompt names the custom style", prompt.contains(CUSTOM_LABEL));
        }
    }
}
