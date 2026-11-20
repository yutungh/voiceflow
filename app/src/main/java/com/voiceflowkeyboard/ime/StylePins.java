package com.voiceflowkeyboard.ime;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which styles sit "On the bar" in the style sheet, the phone's counterpart of
 * desktop's "Show on the bar". Pure, so the rules are unit-tested.
 */
final class StylePins {
    /** How many styles the sheet pins when he has never chosen. */
    static final int DEFAULT_COUNT = 6;

    private StylePins() {
    }

    /**
     * The pinned styles in order. With no stored choice: the everyday styles in
     * list order (the fun ones stay under All styles). A stored id that no longer
     * exists is skipped. The selected style always shows, appended if unpinned.
     */
    static List<String> onBar(List<String> allIds, List<String> funIds, List<String> stored, String selected) {
        Set<String> out = new LinkedHashSet<>();
        if (stored == null) {
            for (String id : allIds) {
                if (out.size() >= DEFAULT_COUNT) {
                    break;
                }
                if (!funIds.contains(id)) {
                    out.add(id);
                }
            }
        } else {
            for (String id : stored) {
                if (allIds.contains(id)) {
                    out.add(id);
                }
            }
        }
        if (selected != null && allIds.contains(selected)) {
            out.add(selected);
        }
        return new ArrayList<>(out);
    }

    /** The stored list after pinning or unpinning one style. */
    static List<String> toggle(List<String> current, String id) {
        List<String> next = new ArrayList<>(current);
        if (!next.remove(id)) {
            next.add(id);
        }
        return next;
    }
}
