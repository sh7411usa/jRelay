package com.sh7411usa.jrelay.util;

import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;

public class RateLimitSettings {
    private static final String KEY_MAX_COUNT = "sms_outgoing_check_max_count";
    private static final String KEY_INTERVAL_MS = "sms_outgoing_check_interval_ms";
    public static final String ADB_GRANT_COMMAND = "adb shell pm grant com.sh7411usa.jrelay android.permission.WRITE_SECURE_SETTINGS";

    public static boolean hasPermission(Context context) {
        return context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED;
    }

    public static int readMaxCount(Context context) {
        try { return Settings.Global.getInt(context.getContentResolver(), KEY_MAX_COUNT, -1); } catch (Exception e) { return -1; }
    }

    public static long readIntervalMs(Context context) {
        try { return Settings.Global.getLong(context.getContentResolver(), KEY_INTERVAL_MS, -1); } catch (Exception e) { return -1; }
    }

    public static boolean writeMaxCount(Context context, int value) {
        try { return Settings.Global.putInt(context.getContentResolver(), KEY_MAX_COUNT, value); } catch (SecurityException e) { return false; }
    }

    public static boolean writeIntervalMs(Context context, long value) {
        try { return Settings.Global.putLong(context.getContentResolver(), KEY_INTERVAL_MS, value); } catch (SecurityException e) { return false; }
    }
}
