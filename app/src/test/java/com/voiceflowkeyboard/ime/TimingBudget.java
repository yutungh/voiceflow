package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertTrue;

import java.util.Arrays;

/**
 * Wall-clock guard that does not flake on cold JVMs or busy CI runners.
 *
 * <p>The old single-worst-call assertions failed whenever one call hit JIT
 * compilation, class loading or a noisy neighbour. This warms the code up with
 * several full runs, then times many repetitions and asserts on the median
 * and the 90th percentile. A real regression moves the whole distribution; a
 * one-off hiccup does not. Timings are printed so CI logs show them.
 */
final class TimingBudget {
    static final int WARMUP_RUNS = 8;
    static final int MEASURED_RUNS = 30;

    private TimingBudget() {
    }

    /** One timed unit of work: a single keystroke or call. */
    interface Step {
        void run(int index);
    }

    /**
     * Runs {@code steps} calls per pass, warm-up passes first, then measured
     * passes. Asserts median and p90 of the per-call times (microseconds).
     */
    static void assertWithin(String label, int steps, Step step, long medianBudgetMicros, long p90BudgetMicros) {
        for (int w = 0; w < WARMUP_RUNS; w++) {
            for (int i = 0; i < steps; i++) {
                step.run(i);
            }
        }
        long[] micros = new long[MEASURED_RUNS * steps];
        int n = 0;
        for (int r = 0; r < MEASURED_RUNS; r++) {
            for (int i = 0; i < steps; i++) {
                long start = System.nanoTime();
                step.run(i);
                micros[n++] = (System.nanoTime() - start) / 1000;
            }
        }
        Arrays.sort(micros);
        long median = micros[micros.length / 2];
        long p90 = micros[(int) (micros.length * 0.9) - 1];
        long max = micros[micros.length - 1];
        System.out.println("[timing] " + label + ": median=" + median + "us p90=" + p90 + "us max=" + max
                + "us (n=" + micros.length + ", budgets median<" + medianBudgetMicros + "us p90<" + p90BudgetMicros + "us)");
        assertTrue(label + " median " + median + "us over " + medianBudgetMicros + "us", median < medianBudgetMicros);
        assertTrue(label + " p90 " + p90 + "us over " + p90BudgetMicros + "us", p90 < p90BudgetMicros);
    }
}
