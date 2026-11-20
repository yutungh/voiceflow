package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The recording panel's chips: desktop's chipRow and nextKept rules, on the phone (0.8.6). */
public class RecordingChipsTest {
    private static final List<String> ALL = Arrays.asList("clean", "casual", "business", "family", "partner",
            "raw", "fun_pirate", "fun_haiku");

    @Test public void hisPinsInHisOrderAtMostFive() {
        List<String> pins = Arrays.asList("partner", "clean", "casual", "business", "family", "raw");
        RecordingChips.Row row = RecordingChips.row(ALL, pins, "partner", null);
        assertEquals(Arrays.asList("partner", "clean", "casual", "business", "family"), row.chips);
        assertEquals(Arrays.asList("raw", "fun_pirate", "fun_haiku"), row.more);
    }

    @Test public void theStyleInUseTakesTheFifthPlaceWhenNotPinned() {
        List<String> pins = Arrays.asList("clean", "casual", "business", "family", "partner");
        RecordingChips.Row row = RecordingChips.row(ALL, pins, "fun_pirate", null);
        assertEquals(Arrays.asList("clean", "casual", "business", "family", "fun_pirate"), row.chips);
        assertEquals(Arrays.asList("partner", "raw", "fun_haiku"), row.more);
    }

    @Test public void fewerPinsLeaveRoomAndTheStyleInUseJoinsAtTheEnd() {
        RecordingChips.Row row = RecordingChips.row(ALL, Arrays.asList("clean", "casual"), "raw", null);
        assertEquals(Arrays.asList("clean", "casual", "raw"), row.chips);
    }

    @Test public void aStyleFromMoreKeepsItsPlaceAfterAnotherChipIsPicked() {
        List<String> pins = Arrays.asList("clean", "casual", "business", "family", "partner");
        String kept = RecordingChips.nextKept(ALL, pins, "fun_haiku", null);
        assertEquals("fun_haiku", kept);
        // He then taps Clean: Haiku stays where it was, nothing moves under his thumb.
        String stillKept = RecordingChips.nextKept(ALL, pins, "clean", kept);
        assertEquals("fun_haiku", stillKept);
        RecordingChips.Row row = RecordingChips.row(ALL, pins, "clean", stillKept);
        assertEquals(Arrays.asList("clean", "casual", "business", "family", "fun_haiku"), row.chips);
    }

    @Test public void pinsThatNoLongerExistAreSkipped() {
        RecordingChips.Row row = RecordingChips.row(ALL, Arrays.asList("gone", "clean"), "clean", null);
        assertEquals(Collections.singletonList("clean"), row.chips);
    }

    @Test public void noPinsMeansTheFirstFive() {
        RecordingChips.Row row = RecordingChips.row(ALL, null, "clean", null);
        assertEquals(Arrays.asList("clean", "casual", "business", "family", "partner"), row.chips);
    }

    @Test public void whenEveryStyleIsAChipNothingIsKeptAndMoreIsEmpty() {
        List<String> few = Arrays.asList("clean", "raw");
        assertNull(RecordingChips.nextKept(few, few, "raw", null));
        assertEquals(0, RecordingChips.row(few, few, "raw", null).more.size());
    }
}
