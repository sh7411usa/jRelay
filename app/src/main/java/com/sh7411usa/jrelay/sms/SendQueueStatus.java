package com.sh7411usa.jrelay.sms;

public class SendQueueStatus {
    private static volatile long nextBurstAtMillis = 0;
    private static volatile int nextBurstSize = 0;

    private SendQueueStatus() {}

    public static void setNextBurst(long atMillis, int size) {
        nextBurstAtMillis = atMillis;
        nextBurstSize = size;
    }

    public static void clear() {
        setNextBurst(0, 0);
    }

    public static long getNextBurstAtMillis() {
        return nextBurstAtMillis;
    }

    public static int getNextBurstSize() {
        return nextBurstSize;
    }
}
