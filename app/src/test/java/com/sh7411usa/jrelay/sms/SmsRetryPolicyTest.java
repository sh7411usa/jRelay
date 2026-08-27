package com.sh7411usa.jrelay.sms;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SmsRetryPolicyTest {
    @Test
    public void retriesUseBoundedBackoff() {
        assertEquals(15 * 60_000L, SmsRetryPolicy.delayForAttemptCount(1));
        assertEquals(30 * 60_000L, SmsRetryPolicy.delayForAttemptCount(2));
        assertEquals(60 * 60_000L, SmsRetryPolicy.delayForAttemptCount(3));
        assertEquals(-1, SmsRetryPolicy.delayForAttemptCount(4));
        assertEquals(-1, SmsRetryPolicy.delayForAttemptCount(0));
    }
}
