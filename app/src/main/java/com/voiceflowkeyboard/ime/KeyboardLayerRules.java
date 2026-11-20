package com.voiceflowkeyboard.ime;

import android.text.InputType;

/**
 * Pure rules for which layer a field opens on, and which spare keys the number
 * pad offers for it.
 */
final class KeyboardLayerRules {

    /** The typing layers. The emoji picker and history panel sit outside these. */
    enum Layer { LETTERS, SYMBOLS, MORE_SYMBOLS, NUMPAD }

    /** Label of the number pad's space key; it types a plain space. */
    static final String SPACE = "space";

    private KeyboardLayerRules() {
    }

    /**
     * The layer to show when a field starts.
     *
     * <p>When the same field restarts, whatever is on screen stays. Some
     * confirmation-code editors restart after every digit, and a layer picked
     * by hand (ABC over a number pad, or the number pad in a text field) must
     * survive that. A different field decides afresh from its type, so a layer
     * never carries over from one field to the next.
     */
    static Layer layerFor(int inputType, boolean sameField, Layer current) {
        if (sameField) {
            return current;
        }
        switch (inputType & InputType.TYPE_MASK_CLASS) {
            case InputType.TYPE_CLASS_NUMBER:
            case InputType.TYPE_CLASS_PHONE:
                return Layer.NUMPAD;
            case InputType.TYPE_CLASS_DATETIME:
                return Layer.SYMBOLS;
            default:
                return Layer.LETTERS;
        }
    }

    /**
     * The number pad's three spare keys, in order: beside 4-5-6, left of 0 and
     * right of 0. An empty string leaves that slot blank.
     */
    static String[] numpadExtras(int inputType) {
        int inputClass = inputType & InputType.TYPE_MASK_CLASS;
        if (inputClass == InputType.TYPE_CLASS_PHONE) {
            return new String[]{"+", "*", "#"};
        }
        if (inputClass == InputType.TYPE_CLASS_NUMBER) {
            if ((inputType & InputType.TYPE_MASK_VARIATION) == InputType.TYPE_NUMBER_VARIATION_PASSWORD) {
                return new String[]{"", "", ""};
            }
            boolean signed = (inputType & InputType.TYPE_NUMBER_FLAG_SIGNED) != 0;
            boolean decimal = (inputType & InputType.TYPE_NUMBER_FLAG_DECIMAL) != 0;
            return new String[]{"", signed ? "-" : "", decimal ? "." : ""};
        }
        if (inputClass == InputType.TYPE_CLASS_DATETIME) {
            return new String[]{"-", "/", ":"};
        }
        // Opened by hand in a text field: card numbers want a space, codes a dash.
        return new String[]{SPACE, "-", "."};
    }
}
