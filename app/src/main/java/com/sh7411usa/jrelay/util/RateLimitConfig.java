package com.sh7411usa.jrelay.util;

// Values are clamped on read (not just on save) so out-of-range values stored by older builds can't wedge or crash the drain thread.
public class RateLimitConfig {
    public static final int MAX_WAIT_SECONDS_CEILING = 3600;
    public static final int BURST_SIZE_CEILING = 50;
    public static final int MICROSPACING_MS_CEILING = 10_000;

    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public final int burstMin, burstMax, minWaitSeconds, maxWaitSeconds;
    public final boolean initialDelayEnabled, staggeringEnabled;
    public final Prefs.BurstMode burstMode;
    public final int fixedBurstSize;
    public final boolean microspacingEnabled;
    public final Prefs.MicrospacingMode microspacingMode;
    public final int microspacingFixedMs, microspacingMinMs, microspacingMaxMs;

    private RateLimitConfig(Prefs prefs) {
        burstMin = clamp(prefs.getBurstMin(), 1, BURST_SIZE_CEILING);
        burstMax = clamp(prefs.getBurstMax(), burstMin, BURST_SIZE_CEILING);
        minWaitSeconds = clamp(prefs.getMinWaitSeconds(), 0, MAX_WAIT_SECONDS_CEILING);
        maxWaitSeconds = clamp(prefs.getMaxWaitSeconds(), minWaitSeconds, MAX_WAIT_SECONDS_CEILING);
        initialDelayEnabled = prefs.isInitialDelayEnabled();
        staggeringEnabled = prefs.isStaggeringEnabled();
        burstMode = prefs.getBurstMode();
        fixedBurstSize = clamp(prefs.getFixedBurstSize(), 1, BURST_SIZE_CEILING);
        microspacingEnabled = prefs.isMicrospacingEnabled();
        microspacingMode = prefs.getMicrospacingMode();
        microspacingFixedMs = clamp(prefs.getMicrospacingFixedMs(), 0, MICROSPACING_MS_CEILING);
        microspacingMinMs = clamp(prefs.getMicrospacingMinMs(), 0, MICROSPACING_MS_CEILING);
        microspacingMaxMs = clamp(prefs.getMicrospacingMaxMs(), microspacingMinMs, MICROSPACING_MS_CEILING);
    }

    public static RateLimitConfig groupDefault(Prefs prefs) {
        return new RateLimitConfig(prefs);
    }
}
