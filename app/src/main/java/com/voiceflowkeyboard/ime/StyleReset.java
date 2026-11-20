package com.voiceflowkeyboard.ime;

import android.content.Context;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * A one-time, device-bound reset of the styles to Clean.
 *
 * <p>An instruction names one user and one device. It runs only on a phone that
 * is enrolled, as that same user, with that same device id, and only once: the
 * instruction's id is recorded before the styles are touched, so a crash halfway
 * can never run it a second time. An un-enrolled phone, another user's phone,
 * or another device of the same user is never reset.
 */
final class StyleReset {
    static final String APPLY = "";
    static final String NOT_ENROLLED = "not-enrolled";
    static final String OTHER_USER = "other-user";
    static final String OTHER_DEVICE = "other-device";
    static final String ALREADY_DONE = "already-done";
    static final String BAD_INSTRUCTION = "bad-instruction";

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{2,63}");

    static final class Instruction {
        final String id;
        final String user;
        final String device;

        Instruction(String id, String user, String device) {
            this.id = id;
            this.user = user;
            this.device = device;
        }
    }

    private StyleReset() {
    }

    /** {@link #APPLY} when the reset may run on this phone; otherwise the reason it may not. */
    static String decide(Enrolment enrolment, Instruction instruction, Set<String> done) {
        if (instruction == null
                || instruction.id == null
                || !ID.matcher(instruction.id).matches()
                || !Enrolment.validUser(instruction.user)
                || !Enrolment.validDevice(instruction.device)) {
            return BAD_INSTRUCTION;
        }
        if (enrolment == null) {
            return NOT_ENROLLED;
        }
        if (!enrolment.user.equals(instruction.user)) {
            return OTHER_USER;
        }
        if (!enrolment.device.equals(instruction.device)) {
            return OTHER_DEVICE;
        }
        if (done != null && done.contains(instruction.id)) {
            return ALREADY_DONE;
        }
        return APPLY;
    }

    /** Decides, and when allowed records the instruction as done and resets. Returns the decision. */
    static String run(Context context, Instruction instruction) {
        String decision = decide(SyncStore.enrolment(context), instruction, SyncStore.resetsDone(context));
        if (!APPLY.equals(decision)) {
            return decision;
        }
        SyncStore.markResetDone(context, instruction.id);
        Prefs.resetStylesToClean(context);
        return APPLY;
    }
}
