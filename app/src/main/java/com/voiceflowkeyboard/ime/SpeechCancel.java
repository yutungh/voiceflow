package com.voiceflowkeyboard.ime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

/**
 * 0.9.3: cancels one dictation's speech request, its fallback included. A cancel stops the
 * Soniox poll at once and disconnects any request in flight; the result, if one still
 * arrives, is never inserted (the caller checks {@link #isCancelled()} before delivering).
 */
final class SpeechCancel {
    private boolean cancelled;
    private final List<Runnable> listeners = new ArrayList<>();

    void cancel() {
        List<Runnable> run;
        synchronized (this) {
            if (cancelled) {
                return;
            }
            cancelled = true;
            run = new ArrayList<>(listeners);
            listeners.clear();
        }
        for (Runnable listener : run) {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
                // A disconnect that fails changes nothing: the flag is already set.
            }
        }
    }

    synchronized boolean isCancelled() {
        return cancelled;
    }

    void throwIfCancelled() {
        if (isCancelled()) {
            throw new CancellationException("Canceled");
        }
    }

    /** Runs {@code listener} on cancel (at once if already cancelled). */
    void onCancel(Runnable listener) {
        synchronized (this) {
            if (!cancelled) {
                listeners.add(listener);
                return;
            }
        }
        listener.run();
    }

    synchronized void removeOnCancel(Runnable listener) {
        listeners.remove(listener);
    }
}
