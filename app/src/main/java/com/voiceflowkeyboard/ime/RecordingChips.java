package com.voiceflowkeyboard.ime;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The style chips on the recording panel (0.8.6): the phone's port of desktop's
 * {@code chipRow} and {@code nextKept} (desktop 0.9.9+, {@code lib/bar/chips.ts}).
 * Pure, so the rules are unit-tested; the service draws the result.
 *
 * <ul>
 *   <li>At most {@link #MAX_CHIPS}: the user's pinned styles ("On the bar"), in the user's order.</li>
 *   <li>The style in use is always a chip: when it is not among the first four
 *       pinned, it takes the fifth place. A style picked from More keeps that
 *       place for the rest of the recording, so the row never reshuffles under
 *       their thumb.</li>
 *   <li>Everything else is under More, in the styles' own order.</li>
 * </ul>
 */
final class RecordingChips {
    static final int MAX_CHIPS = 5;

    /** The chips in order, and the rest for More. */
    static final class Row {
        final List<String> chips;
        final List<String> more;

        Row(List<String> chips, List<String> more) {
            this.chips = chips;
            this.more = more;
        }
    }

    private RecordingChips() {
    }

    private static List<String> unique(List<String> ids) {
        return new ArrayList<>(new LinkedHashSet<>(ids));
    }

    /** The pinned styles that still exist, in the user's order, at most {@code max}. */
    private static List<String> favourites(List<String> all, List<String> pinned, int max) {
        List<String> out = new ArrayList<>();
        for (String id : pinned == null ? all : pinned) {
            if (all.contains(id) && !out.contains(id)) {
                out.add(id);
            }
            if (out.size() >= max) {
                break;
            }
        }
        return out;
    }

    /**
     * The chip row.
     *
     * @param allIds every style, in the styles' order
     * @param pinned the user's pinned styles, in the user's order (null: the first ones)
     * @param currentId the style in use (always a chip when it exists)
     * @param keptId a style picked from More earlier in this recording, or null
     */
    static Row row(List<String> allIds, List<String> pinned, String currentId, String keptId) {
        return row(allIds, pinned, currentId, keptId, MAX_CHIPS);
    }

    static Row row(List<String> allIds, List<String> pinned, String currentId, String keptId, int max) {
        List<String> all = unique(allIds);
        List<String> fav = favourites(all, pinned, max);
        boolean full = fav.size() >= max;
        List<String> head = full ? new ArrayList<>(fav.subList(0, max - 1)) : new ArrayList<>(fav);
        String last = null;
        if (currentId != null && all.contains(currentId) && !head.contains(currentId)) {
            last = currentId;
        } else if (keptId != null && all.contains(keptId) && !head.contains(keptId)) {
            last = keptId;
        } else if (full) {
            last = fav.get(max - 1);
        }
        List<String> chips = new ArrayList<>(head);
        if (last != null) {
            chips.add(last);
        }
        List<String> more = new ArrayList<>();
        for (String id : all) {
            if (!chips.contains(id)) {
                more.add(id);
            }
        }
        return new Row(chips, more);
    }

    /**
     * The style that keeps the fifth place after a pick: the style in use when it
     * is not one of the fixed places, otherwise whatever was kept before.
     */
    static String nextKept(List<String> allIds, List<String> pinned, String currentId, String keptId) {
        return nextKept(allIds, pinned, currentId, keptId, MAX_CHIPS);
    }

    static String nextKept(List<String> allIds, List<String> pinned, String currentId, String keptId, int max) {
        List<String> all = unique(allIds);
        List<String> fav = favourites(all, pinned, max);
        if (fav.size() == all.size() && fav.size() <= max) {
            return null;
        }
        List<String> head = fav.size() >= max ? fav.subList(0, max - 1) : fav;
        return head.contains(currentId) ? keptId : currentId;
    }
}
