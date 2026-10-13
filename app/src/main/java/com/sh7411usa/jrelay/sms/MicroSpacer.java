package com.sh7411usa.jrelay.sms;

import java.util.concurrent.locks.LockSupport;

// Not Thread.sleep: re-measures against a fixed nanosecond deadline so short waits don't drift.
public final class MicroSpacer {
    private static final long MAX_SLICE_NANOS = 2_000_000L;

    private MicroSpacer() {}

    public static void waitMillis(double millis) {
        if (millis <= 0) return;
        long deadlineNanos = System.nanoTime() + Math.round(millis * 1_000_000.0);
        long remaining;
        while ((remaining = deadlineNanos - System.nanoTime()) > 0) {
            LockSupport.parkNanos(Math.min(remaining, MAX_SLICE_NANOS));
        }
    }
}
