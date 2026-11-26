package com.voiceflowkeyboard.ime;

import android.content.Context;

import java.util.Collections;
import java.util.List;

/**
 * 0.9.3: the styles a word list defines ({@code styles[]} in a v1 layer file),
 * offered on the phone next to its own styles, and the word lists' default style
 * ({@code defaults.cleanupStyle}), applied once.
 *
 * <ul>
 *   <li>A word-list style is the style id {@value #PREFIX} plus its id in the list. It is
 *       never stored in the phone's own style list, can't be edited or deleted on the
 *       phone, and goes away when the list drops it. Its prompt is wrapped in the same
 *       language rules and checked by the same language guard as every other everyday
 *       style. The list's {@code model} is not used: a list may name a computer's model.</li>
 *   <li>The default is applied once each time its merged value changes: the phone switches
 *       to that style, and a style picked on the phone afterwards stays (on a phone the
 *       style picked on the strip is the default). {@code raw}, or a style this phone
 *       doesn't have, is recorded as seen and changes nothing.</li>
 * </ul>
 *
 * Read on the keyboard's main thread from {@link #published}, which only ever holds the
 * last snapshot built: nothing here reads a file or merges.
 */
final class ListStyles {
    static final String PREFIX = "list_";

    private static volatile List<WordLayer.Style> published = Collections.emptyList();
    private static final Object APPLY_LOCK = new Object();

    private ListStyles() {
    }

    static boolean isListStyle(String id) {
        return id != null && id.startsWith(PREFIX) && id.length() > PREFIX.length();
    }

    static String styleId(String listStyleId) {
        return listStyleId.substring(PREFIX.length());
    }

    /** The word lists' styles from the last snapshot built (empty when there is none). */
    static List<WordLayer.Style> published() {
        return published;
    }

    /** The word-list style behind a {@value #PREFIX} id, or null when the lists don't have it (any more). */
    static WordLayer.Style find(String listStyleId) {
        if (!isListStyle(listStyleId)) {
            return null;
        }
        String id = styleId(listStyleId);
        for (WordLayer.Style style : published) {
            if (style.id.equals(id)) {
                return style;
            }
        }
        return null;
    }

    /**
     * Called with each current snapshot built. A null snapshot (not enrolled, or no
     * copy accepted yet) empties the styles but leaves the default's record alone.
     */
    static void publish(Context context, WordMerge.Snapshot snapshot) {
        published = snapshot == null ? Collections.<WordLayer.Style>emptyList() : snapshot.styles;
        if (snapshot != null && context != null) {
            applyDefaultOnce(context, snapshot);
        }
    }

    /**
     * The phone style a word lists' default names: a style of the lists first (a list may
     * define its own "clean"), then one of the built-in styles from the pack. "" when this
     * phone has no such style (raw included).
     */
    static String target(WordMerge.Snapshot snapshot, String cleanupStyle) {
        if (cleanupStyle == null || "raw".equals(cleanupStyle)) {
            return ""; // raw means no tidy-up, even where a list has a style called raw
        }
        for (WordLayer.Style style : snapshot.styles) {
            if (style.id.equals(cleanupStyle)) {
                return PREFIX + style.id;
            }
        }
        return BuiltinStyles.find(cleanupStyle) != null ? cleanupStyle : "";
    }

    static void applyDefaultOnce(Context context, WordMerge.Snapshot snapshot) {
        Object value = snapshot.defaults.get("cleanupStyle");
        String cleanupStyle = value instanceof String ? (String) value : null;
        synchronized (APPLY_LOCK) {
            Prefs.applyListDefault(context, cleanupStyle, target(snapshot, cleanupStyle));
        }
    }

    /** Tests: forget the published styles. */
    static void resetForTest() {
        published = Collections.emptyList();
    }
}
