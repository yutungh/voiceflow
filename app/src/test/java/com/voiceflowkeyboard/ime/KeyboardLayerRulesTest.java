package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import android.text.InputType;

import com.voiceflowkeyboard.ime.KeyboardLayerRules.Layer;

import org.junit.Test;

public class KeyboardLayerRulesTest {

    private static final int TEXT = InputType.TYPE_CLASS_TEXT;
    private static final int NUMBER = InputType.TYPE_CLASS_NUMBER;

    @Test
    public void numberAndPhoneFieldsOpenOnTheNumberPad() {
        assertEquals(Layer.NUMPAD, KeyboardLayerRules.layerFor(NUMBER, false, Layer.LETTERS));
        assertEquals(Layer.NUMPAD, KeyboardLayerRules.layerFor(
                NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD, false, Layer.LETTERS));
        assertEquals(Layer.NUMPAD, KeyboardLayerRules.layerFor(InputType.TYPE_CLASS_PHONE, false, Layer.LETTERS));
    }

    @Test
    public void dateAndTimeFieldsOpenOnSymbols() {
        assertEquals(Layer.SYMBOLS, KeyboardLayerRules.layerFor(InputType.TYPE_CLASS_DATETIME, false, Layer.LETTERS));
    }

    @Test
    public void ordinaryTextFieldStartsOnLetters() {
        assertEquals(Layer.LETTERS, KeyboardLayerRules.layerFor(
                TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL, false, Layer.LETTERS));
    }

    @Test
    public void aNewFieldNeverInheritsTheLastFieldsLayer() {
        // The symbols layer used to follow the user from a phone field into Name.
        assertEquals(Layer.LETTERS, KeyboardLayerRules.layerFor(TEXT, false, Layer.SYMBOLS));
        assertEquals(Layer.LETTERS, KeyboardLayerRules.layerFor(TEXT, false, Layer.NUMPAD));
        assertEquals(Layer.NUMPAD, KeyboardLayerRules.layerFor(NUMBER, false, Layer.MORE_SYMBOLS));
    }

    @Test
    public void theSameFieldRestartingKeepsWhatIsOnScreen() {
        // A code field that restarts after every digit keeps a hand-picked layer,
        // including ABC chosen over a number pad.
        assertEquals(Layer.NUMPAD, KeyboardLayerRules.layerFor(TEXT, true, Layer.NUMPAD));
        assertEquals(Layer.LETTERS, KeyboardLayerRules.layerFor(NUMBER, true, Layer.LETTERS));
        assertEquals(Layer.MORE_SYMBOLS, KeyboardLayerRules.layerFor(TEXT, true, Layer.MORE_SYMBOLS));
    }

    @Test
    public void phoneFieldsGetDialPadSymbols() {
        assertArrayEquals(new String[]{"+", "*", "#"},
                KeyboardLayerRules.numpadExtras(InputType.TYPE_CLASS_PHONE));
    }

    @Test
    public void numberFieldsGetOnlyTheMarksTheyAccept() {
        assertArrayEquals(new String[]{"", "", ""}, KeyboardLayerRules.numpadExtras(NUMBER));
        assertArrayEquals(new String[]{"", "", "."},
                KeyboardLayerRules.numpadExtras(NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL));
        assertArrayEquals(new String[]{"", "-", ""},
                KeyboardLayerRules.numpadExtras(NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED));
        assertArrayEquals(new String[]{"", "-", "."}, KeyboardLayerRules.numpadExtras(
                NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED | InputType.TYPE_NUMBER_FLAG_DECIMAL));
    }

    @Test
    public void aPinPadHasNoSpareKeys() {
        assertArrayEquals(new String[]{"", "", ""},
                KeyboardLayerRules.numpadExtras(NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD));
    }

    @Test
    public void aTextFieldOpenedByHandGetsCardAndCodeSeparators() {
        assertArrayEquals(new String[]{KeyboardLayerRules.SPACE, "-", "."},
                KeyboardLayerRules.numpadExtras(TEXT));
    }
}
