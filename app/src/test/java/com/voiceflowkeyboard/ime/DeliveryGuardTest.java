package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** Safe text replacement: only the verified last insertion, never another field. */
public class DeliveryGuardTest {
    private static final String FIELD = "org.example.chat:12:131073";
    private static final DeliveryGuard.Target CHAT = new DeliveryGuard.Target("org.example.chat", FIELD, 7);
    private static final String TEXT = " Running ten minutes late to Pinewood.";
    private static final DeliveryGuard.Insertion LAST = new DeliveryGuard.Insertion(CHAT, 57, TEXT);

    @Test
    public void replacesTheVerifiedLastInsertion() {
        assertEquals(DeliveryGuard.Verdict.OK, DeliveryGuard.mayReplace(LAST, CHAT, 0, 57, TEXT));
    }

    @Test
    public void anUnknownCursorRefusesEvenWithTheSameText() {
        // The same words earlier in the field would look identical without positions.
        assertEquals(DeliveryGuard.Verdict.CURSOR_UNKNOWN, DeliveryGuard.mayReplace(LAST, CHAT, 0, -1, TEXT));
        assertEquals(DeliveryGuard.Verdict.CURSOR_UNKNOWN,
                DeliveryGuard.mayReplace(new DeliveryGuard.Insertion(CHAT, -1, TEXT), CHAT, 0, 57, TEXT));
    }

    @Test
    public void neverReplacesInAnotherFieldOrSession() {
        assertEquals(DeliveryGuard.Verdict.OTHER_FIELD, DeliveryGuard.mayReplace(LAST,
                new DeliveryGuard.Target("org.example.chat", "org.example.chat:13:131073", 7), 0, 57, TEXT));
        assertEquals(DeliveryGuard.Verdict.OTHER_FIELD, DeliveryGuard.mayReplace(LAST,
                new DeliveryGuard.Target("org.example.mail", FIELD, 7), 0, 57, TEXT));
        // Same metadata, but focus moved away and back: a new input session.
        assertEquals(DeliveryGuard.Verdict.OTHER_FIELD, DeliveryGuard.mayReplace(LAST,
                new DeliveryGuard.Target("org.example.chat", FIELD, 8), 0, 57, TEXT));
        assertEquals(DeliveryGuard.Verdict.NO_EDITOR,
                DeliveryGuard.mayReplace(LAST, new DeliveryGuard.Target("", "", 7), 0, 57, TEXT));
    }

    @Test
    public void refusesWhenTheCursorMovedOrTextWasSelected() {
        assertEquals(DeliveryGuard.Verdict.CURSOR_MOVED, DeliveryGuard.mayReplace(LAST, CHAT, 0, 40, TEXT));
        assertEquals(DeliveryGuard.Verdict.SELECTION_ACTIVE, DeliveryGuard.mayReplace(LAST, CHAT, 5, 57, TEXT));
        assertEquals(DeliveryGuard.Verdict.SELECTION_ACTIVE, DeliveryGuard.mayReplace(LAST, CHAT, -1, 57, TEXT));
    }

    @Test
    public void refusesWhenHeEditedTheTextOrTheEditorWillNotSay() {
        assertEquals(DeliveryGuard.Verdict.TEXT_CHANGED,
                DeliveryGuard.mayReplace(LAST, CHAT, 0, 57, " Running fifteen minutes late to Pinewood."));
        assertEquals(DeliveryGuard.Verdict.TEXT_UNKNOWN, DeliveryGuard.mayReplace(LAST, CHAT, 0, 57, null));
        assertEquals(DeliveryGuard.Verdict.NOTHING_TO_REPLACE,
                DeliveryGuard.mayReplace(new DeliveryGuard.Insertion(CHAT, 57, ""), CHAT, 0, 57, TEXT));
    }

    @Test
    public void aDictationGoesOnlyIntoTheEditorItStartedIn() {
        assertEquals(DeliveryGuard.Verdict.OK, DeliveryGuard.mayInsert(CHAT, CHAT, true));
        assertEquals(DeliveryGuard.Verdict.OTHER_APP,
                DeliveryGuard.mayInsert(CHAT, new DeliveryGuard.Target("org.example.mail", FIELD, 7), true));
        assertEquals(DeliveryGuard.Verdict.OTHER_FIELD,
                DeliveryGuard.mayInsert(CHAT, new DeliveryGuard.Target("org.example.chat", "org.example.chat:3:1", 7), true));
        assertEquals(DeliveryGuard.Verdict.OTHER_FIELD,
                DeliveryGuard.mayInsert(CHAT, new DeliveryGuard.Target("org.example.chat", FIELD, 9), true));
        assertEquals(DeliveryGuard.Verdict.NO_EDITOR, DeliveryGuard.mayInsert(CHAT, CHAT, false));
        assertEquals(DeliveryGuard.Verdict.OTHER_APP,
                DeliveryGuard.mayInsert(new DeliveryGuard.Target("", "", -1), CHAT, true));
    }

    @Test
    public void aVoiceEditNeedsTheSameEditorAndAProvablyCompleteUnchangedField() {
        String source = "Milo, the QBR moved to Friday.";
        assertEquals(DeliveryGuard.Verdict.OK, DeliveryGuard.mayRewriteField(CHAT, CHAT, source, source));
        assertEquals(DeliveryGuard.Verdict.TEXT_CHANGED, DeliveryGuard.mayRewriteField(CHAT, CHAT, source, source + " Thanks"));
        assertEquals(DeliveryGuard.Verdict.TEXT_UNKNOWN, DeliveryGuard.mayRewriteField(CHAT, CHAT, source, null));
        assertEquals(DeliveryGuard.Verdict.OTHER_FIELD, DeliveryGuard.mayRewriteField(CHAT,
                new DeliveryGuard.Target("org.example.chat", FIELD, 8), source, source));
    }

    @Test
    public void aFieldIsCompleteOnlyWhenTwoReadsAgree() {
        assertEquals("Hello Febe", DeliveryGuard.completeField("Hello Febe", 0, "Hello ", "", "Febe", 1000));
        assertEquals("Hello Febe", DeliveryGuard.completeField("Hello Febe", 0, "Hello ", null, "Febe", 1000));
        // A slice of a longer field.
        assertNull(DeliveryGuard.completeField("Hello Febe", 40, "Hello ", "", "Febe", 1000));
        // The reads disagree: something outside the extraction.
        assertNull(DeliveryGuard.completeField("Hello Febe", 0, "Earlier text. Hello ", "", "Febe", 1000));
        // A read that hit its limit may be truncated.
        assertNull(DeliveryGuard.completeField("Hello", 0, "Hello", "", "", 5));
        assertNull(DeliveryGuard.completeField(null, 0, "Hello", "", "", 1000));
        assertNull(DeliveryGuard.completeField("Hello", 0, null, "", "", 1000));
    }
}
