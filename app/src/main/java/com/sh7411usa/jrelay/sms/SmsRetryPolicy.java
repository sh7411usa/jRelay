package com.sh7411usa.jrelay.sms;

public final class SmsRetryPolicy {
    private static final long MINUTE = 60_000L;
    private static final long[] DELAYS = {15 * MINUTE, 30 * MINUTE, 60 * MINUTE};

    private SmsRetryPolicy() { }

    /** attemptCount includes the attempt that just failed. */
    public static long delayForAttemptCount(int attemptCount) {
        return attemptCount >= 1 && attemptCount <= DELAYS.length ? DELAYS[attemptCount - 1] : -1;
    }
}
