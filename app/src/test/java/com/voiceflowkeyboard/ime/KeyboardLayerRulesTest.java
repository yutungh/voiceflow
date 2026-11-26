package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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

    @Test
    public void numberOnlyFieldsShowDialerLetters() {
        int[] lettered = {NUMBER, NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD,
                NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL, InputType.TYPE_CLASS_PHONE};
        for (int type : lettered) {
            assertTrue(KeyboardLayerRules.showsDialLetters(type));
            assertEquals("ABC", KeyboardLayerRules.dialLetters("2", type));
            assertEquals("DEF", KeyboardLayerRules.dialLetters("3", type));
            assertEquals("GHI", KeyboardLayerRules.dialLetters("4", type));
            assertEquals("JKL", KeyboardLayerRules.dialLetters("5", type));
            assertEquals("MNO", KeyboardLayerRules.dialLetters("6", type));
            assertEquals("PQRS", KeyboardLayerRules.dialLetters("7", type));
            assertEquals("TUV", KeyboardLayerRules.dialLetters("8", type));
            assertEquals("WXYZ", KeyboardLayerRules.dialLetters("9", type));
            assertEquals("", KeyboardLayerRules.dialLetters("1", type));
            assertEquals("", KeyboardLayerRules.dialLetters("0", type));
            assertEquals("", KeyboardLayerRules.dialLetters("+", type));
            assertEquals("", KeyboardLayerRules.dialLetters("#", type));
        }
    }

    @Test
    public void aPadOpenedByHandInATextOrDateFieldHasPlainDigits() {
        int[] plain = {TEXT, TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_CLASS_DATETIME};
        for (int type : plain) {
            assertFalse(KeyboardLayerRules.showsDialLetters(type));
            assertEquals("", KeyboardLayerRules.dialLetters("2", type));
            assertEquals("", KeyboardLayerRules.dialLetters("9", type));
        }
    }

    @Test
    public void aScreenReaderSpellsTheDialerLetters() {
        assertEquals("2, A B C", KeyboardLayerRules.dialDescription("2", "ABC"));
        assertEquals("7, P Q R S", KeyboardLayerRules.dialDescription("7", "PQRS"));
        assertEquals("1", KeyboardLayerRules.dialDescription("1", ""));
    }
}
