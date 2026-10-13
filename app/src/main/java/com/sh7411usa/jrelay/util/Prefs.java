package com.sh7411usa.jrelay.util;

import android.content.Context;
import android.content.SharedPreferences;

import com.sh7411usa.jrelay.R;
import com.sh7411usa.jrelay.sms.SmsCommand;

import java.util.Set;

public class Prefs {
    public enum BurstMode { RANDOM_RANGE, FIXED, ALL_AT_ONCE }
    public enum MicrospacingMode { FIXED, RANDOM_RANGE }
    public enum SaltTimestampFormat { TIME_HHMMSS, UNIX_SECONDS, HEX_SECONDS, HEX_MILLIS }
    public enum SaltHexPosition { APPEND, PREPEND }
    public enum ThemeChoice { SYSTEM, LIGHT, DARK }
    public enum GroupMode { GROUP, ANNOUNCEMENT, REPLY }
    public enum JoinPolicy { OFF, ALLOW, REQUIRE_APPROVAL }
    public enum DeliveryMode { SMS, GROUP_MMS }

    public static final long PAUSE_INDEFINITE = Long.MAX_VALUE;
    public static final String LANGUAGE_SYSTEM = "system";
    public static final String LANGUAGE_ENGLISH = "en";
    public static final String LANGUAGE_HEBREW = "iw";
    public static final String LANGUAGE_YIDDISH = "yi";

    private static final String DEFAULT_GROUP_NAME = "jRelay";
    private static final String KEY_CONSENT_ACCEPTED = "consent_accepted", KEY_GROUP_NAME = "group_name", KEY_BURST_MIN = "burst_min";
    private static final String KEY_BURST_MAX = "burst_max", KEY_MIN_WAIT_SECONDS = "min_wait_seconds";
    private static final String KEY_MAX_WAIT_SECONDS = "max_wait_seconds", KEY_INITIAL_DELAY_ENABLED = "initial_delay_enabled";
    private static final String KEY_STAGGERING_ENABLED = "staggering_enabled", KEY_BURST_MODE = "burst_mode";
    private static final String KEY_FIXED_BURST_SIZE = "fixed_burst_size", KEY_MICROSPACING_ENABLED = "microspacing_enabled";
    private static final String KEY_MICROSPACING_MODE = "microspacing_mode", KEY_MICROSPACING_FIXED_MS = "microspacing_fixed_ms";
    private static final String KEY_MICROSPACING_MIN_MS = "microspacing_min_ms", KEY_MICROSPACING_MAX_MS = "microspacing_max_ms";
    private static final String KEY_DELIVERY_SHUFFLE_ENABLED = "delivery_shuffle_enabled";
    private static final String KEY_APPEND_SENDER_NUMBER_ENABLED = "append_sender_number_enabled";
    private static final String KEY_STRIP_PHONE_NUMBERS_ENABLED = "strip_phone_numbers_enabled";
    private static final String KEY_SALT_TIMESTAMP_ENABLED = "salt_timestamp_enabled", KEY_SALT_TIMESTAMP_FORMAT = "salt_timestamp_format";
    private static final String KEY_SALT_HEX_ENABLED = "salt_hex_enabled", KEY_SALT_HEX_POSITION = "salt_hex_position";
    private static final String KEY_SALT_ZWSP_ENABLED = "salt_zwsp_enabled", KEY_RETRY_LIMIT = "retry_limit";
    private static final String KEY_FAILURE_ALERT_THRESHOLD = "failure_alert_threshold";
    private static final String KEY_GROUP_DAILY_LIMIT_ENABLED = "group_daily_limit_enabled";
    private static final String KEY_GROUP_DAILY_LIMIT_VALUE = "group_daily_limit_value";
    private static final String KEY_GROUP_DAILY_LIMIT_BONUS = "group_daily_limit_bonus";
    private static final String KEY_GROUP_DAILY_LIMIT_BONUS_WINDOW_START = "group_daily_limit_bonus_window_start";
    private static final String KEY_DEFAULT_INDIVIDUAL_LIMIT_SEED = "default_individual_limit_seed";
    private static final String KEY_DAILY_LIMIT_RESET_MINUTE_OF_DAY = "daily_limit_reset_minute_of_day", KEY_LANGUAGE_CODE = "language_code";
    private static final String KEY_THEME_CHOICE = "theme_choice", KEY_ADDED_REPORTING_ENABLED = "added_reporting_enabled";
    private static final String KEY_NOTIFY_MEMBER_LEFT = "notify_member_left", KEY_NOTIFY_NAME_CHANGED = "notify_name_changed";
    private static final String KEY_NOTIFY_MEMBER_REMOVED = "notify_member_removed", KEY_NOTIFY_GROUP_RENAMED = "notify_group_renamed";
    private static final String KEY_NOTIFY_MODE_CHANGED = "notify_mode_changed", KEY_GROUP_MODE = "group_mode";
    private static final String KEY_REPLY_WINDOW_HOURS = "reply_window_hours", KEY_COPY_REPLIES_TO_ADMINS = "copy_replies_to_admins";
    private static final String KEY_BARE_KEYWORDS_ENABLED = "bare_keywords_enabled", KEY_DISABLED_COMMANDS = "disabled_commands";
    private static final String KEY_COALESCE_WINDOW_SECONDS = "coalesce_window_seconds", KEY_MAX_MERGED_SEGMENTS = "max_merged_segments";
    private static final String KEY_JOIN_POLICY = "join_policy", KEY_PAUSE_UNTIL_MILLIS = "pause_until_millis", KEY_STOPPED = "stopped";
    private static final String KEY_MAX_MEMBERS = "max_members", KEY_GROUP_FULL_MESSAGE = "group_full_message";
    private static final String KEY_DELIVERY_MODE = "delivery_mode", KEY_SUBGROUP_TARGET_SIZE = "subgroup_target_size";
    private static final String KEY_MMS_INGEST_SINCE_SECONDS = "mms_ingest_since_seconds";
    private static final String KEY_SMS_CATCH_UP_SINCE_MILLIS = "sms_catch_up_since_millis";

    private final Context appContext;
    private final SharedPreferences prefs;

    public Prefs(Context context) {
        appContext = context.getApplicationContext();
        prefs = appContext.getSharedPreferences("jrelay_prefs", Context.MODE_PRIVATE);
    }

    private boolean b(String k, boolean def) { return prefs.getBoolean(k, def); }
    private int i(String k, int def) { return prefs.getInt(k, def); }
    private void setI(String k, int v) { prefs.edit().putInt(k, v).apply(); }
    private long l(String k) { return prefs.getLong(k, 0L); }
    private void setL(String k, long v) { prefs.edit().putLong(k, v).apply(); }
    private void setS(String k, String v) { prefs.edit().putString(k, v).apply(); }
    private void setB(String k, boolean v) { prefs.edit().putBoolean(k, v).apply(); }

    private <E extends Enum<E>> E e(String k, Class<E> type, E def) {
        String stored = prefs.getString(k, null);
        if (stored == null) return def;
        try {
            return Enum.valueOf(type, stored);
        } catch (IllegalArgumentException ex) {
            return def;
        }
    }

    public boolean isConsentAccepted() { return b(KEY_CONSENT_ACCEPTED, false); }
    public void setConsentAccepted(boolean v) { setB(KEY_CONSENT_ACCEPTED, v); }
    public String getGroupName() { return prefs.getString(KEY_GROUP_NAME, DEFAULT_GROUP_NAME); }
    public void setGroupName(String name) { setS(KEY_GROUP_NAME, name); }
    public void resetGroupName() { setGroupName(DEFAULT_GROUP_NAME); }

    public int getBurstMin() { return i(KEY_BURST_MIN, 3); }
    public void setBurstMin(int v) { setI(KEY_BURST_MIN, v); }
    public int getBurstMax() { return i(KEY_BURST_MAX, 5); }
    public void setBurstMax(int v) { setI(KEY_BURST_MAX, v); }
    public int getMinWaitSeconds() { return i(KEY_MIN_WAIT_SECONDS, 3); }
    public void setMinWaitSeconds(int v) { setI(KEY_MIN_WAIT_SECONDS, v); }
    public int getMaxWaitSeconds() { return i(KEY_MAX_WAIT_SECONDS, 8); }
    public void setMaxWaitSeconds(int v) { setI(KEY_MAX_WAIT_SECONDS, v); }
    public boolean isInitialDelayEnabled() { return b(KEY_INITIAL_DELAY_ENABLED, true); }
    public void setInitialDelayEnabled(boolean v) { setB(KEY_INITIAL_DELAY_ENABLED, v); }
    public boolean isStaggeringEnabled() { return b(KEY_STAGGERING_ENABLED, true); }
    public void setStaggeringEnabled(boolean v) { setB(KEY_STAGGERING_ENABLED, v); }
    public BurstMode getBurstMode() { return e(KEY_BURST_MODE, BurstMode.class, BurstMode.RANDOM_RANGE); }
    public void setBurstMode(BurstMode m) { setS(KEY_BURST_MODE, m.name()); }
    public int getFixedBurstSize() { return i(KEY_FIXED_BURST_SIZE, 4); }
    public void setFixedBurstSize(int v) { setI(KEY_FIXED_BURST_SIZE, v); }
    public boolean isMicrospacingEnabled() { return b(KEY_MICROSPACING_ENABLED, true); }
    public void setMicrospacingEnabled(boolean v) { setB(KEY_MICROSPACING_ENABLED, v); }
    public MicrospacingMode getMicrospacingMode() { return e(KEY_MICROSPACING_MODE, MicrospacingMode.class, MicrospacingMode.FIXED); }
    public void setMicrospacingMode(MicrospacingMode m) { setS(KEY_MICROSPACING_MODE, m.name()); }
    public int getMicrospacingFixedMs() { return i(KEY_MICROSPACING_FIXED_MS, 350); }
    public void setMicrospacingFixedMs(int v) { setI(KEY_MICROSPACING_FIXED_MS, v); }
    public int getMicrospacingMinMs() { return i(KEY_MICROSPACING_MIN_MS, 150); }
    public void setMicrospacingMinMs(int v) { setI(KEY_MICROSPACING_MIN_MS, v); }
    public int getMicrospacingMaxMs() { return i(KEY_MICROSPACING_MAX_MS, 500); }
    public void setMicrospacingMaxMs(int v) { setI(KEY_MICROSPACING_MAX_MS, v); }
    public boolean isDeliveryShuffleEnabled() { return b(KEY_DELIVERY_SHUFFLE_ENABLED, true); }
    public void setDeliveryShuffleEnabled(boolean v) { setB(KEY_DELIVERY_SHUFFLE_ENABLED, v); }

    public boolean isAppendSenderNumberEnabled() { return b(KEY_APPEND_SENDER_NUMBER_ENABLED, false); }
    public void setAppendSenderNumberEnabled(boolean v) { setB(KEY_APPEND_SENDER_NUMBER_ENABLED, v); }
    public boolean isStripPhoneNumbersEnabled() { return b(KEY_STRIP_PHONE_NUMBERS_ENABLED, false); }
    public void setStripPhoneNumbersEnabled(boolean v) { setB(KEY_STRIP_PHONE_NUMBERS_ENABLED, v); }

    public boolean isSaltTimestampEnabled() { return b(KEY_SALT_TIMESTAMP_ENABLED, false); }
    public void setSaltTimestampEnabled(boolean v) { setB(KEY_SALT_TIMESTAMP_ENABLED, v); }
    public SaltTimestampFormat getSaltTimestampFormat() { return e(KEY_SALT_TIMESTAMP_FORMAT, SaltTimestampFormat.class, SaltTimestampFormat.TIME_HHMMSS); }
    public void setSaltTimestampFormat(SaltTimestampFormat f) { setS(KEY_SALT_TIMESTAMP_FORMAT, f.name()); }
    public boolean isSaltHexEnabled() { return b(KEY_SALT_HEX_ENABLED, false); }
    public void setSaltHexEnabled(boolean v) { setB(KEY_SALT_HEX_ENABLED, v); }
    public SaltHexPosition getSaltHexPosition() { return e(KEY_SALT_HEX_POSITION, SaltHexPosition.class, SaltHexPosition.APPEND); }
    public void setSaltHexPosition(SaltHexPosition p) { setS(KEY_SALT_HEX_POSITION, p.name()); }
    public boolean isSaltZwspEnabled() { return b(KEY_SALT_ZWSP_ENABLED, false); }
    public void setSaltZwspEnabled(boolean v) { setB(KEY_SALT_ZWSP_ENABLED, v); }

    public int getRetryLimit() { return i(KEY_RETRY_LIMIT, 1); }
    public void setRetryLimit(int v) { setI(KEY_RETRY_LIMIT, v); }
    public int getFailureAlertThreshold() { return i(KEY_FAILURE_ALERT_THRESHOLD, 3); }
    public void setFailureAlertThreshold(int v) { setI(KEY_FAILURE_ALERT_THRESHOLD, v); }

    public boolean isGroupDailyLimitEnabled() { return b(KEY_GROUP_DAILY_LIMIT_ENABLED, false); }
    public void setGroupDailyLimitEnabled(boolean v) { setB(KEY_GROUP_DAILY_LIMIT_ENABLED, v); }
    public int getGroupDailyLimitValue() { return i(KEY_GROUP_DAILY_LIMIT_VALUE, 100); }
    public void setGroupDailyLimitValue(int v) { setI(KEY_GROUP_DAILY_LIMIT_VALUE, v); }
    public int getGroupDailyLimitBonus() { return i(KEY_GROUP_DAILY_LIMIT_BONUS, 0); }
    public void setGroupDailyLimitBonus(int v) { setI(KEY_GROUP_DAILY_LIMIT_BONUS, v); }
    public long getGroupDailyLimitBonusWindowStart() { return l(KEY_GROUP_DAILY_LIMIT_BONUS_WINDOW_START); }
    public void setGroupDailyLimitBonusWindowStart(long v) { setL(KEY_GROUP_DAILY_LIMIT_BONUS_WINDOW_START, v); }
    public int getDefaultIndividualLimitSeed() { return i(KEY_DEFAULT_INDIVIDUAL_LIMIT_SEED, 10); }
    public void setDefaultIndividualLimitSeed(int v) { setI(KEY_DEFAULT_INDIVIDUAL_LIMIT_SEED, v); }
    public int getDailyLimitResetMinuteOfDay() { return i(KEY_DAILY_LIMIT_RESET_MINUTE_OF_DAY, 0); }
    public void setDailyLimitResetMinuteOfDay(int v) { setI(KEY_DAILY_LIMIT_RESET_MINUTE_OF_DAY, v); }

    public String getLanguageCode() { return prefs.getString(KEY_LANGUAGE_CODE, LANGUAGE_SYSTEM); }
    public void setLanguageCode(String code) { setS(KEY_LANGUAGE_CODE, code); }
    public ThemeChoice getThemeChoice() { return e(KEY_THEME_CHOICE, ThemeChoice.class, ThemeChoice.SYSTEM); }
    public void setThemeChoice(ThemeChoice c) { setS(KEY_THEME_CHOICE, c.name()); }

    public boolean isAddedReportingEnabled() { return b(KEY_ADDED_REPORTING_ENABLED, true); }
    public void setAddedReportingEnabled(boolean v) { setB(KEY_ADDED_REPORTING_ENABLED, v); }
    // Group-wide announcements; left/name/removed/renamed default false on purpose (SMS budget), mode-changed true.
    public boolean isNotifyMemberLeftEnabled() { return b(KEY_NOTIFY_MEMBER_LEFT, false); }
    public void setNotifyMemberLeftEnabled(boolean v) { setB(KEY_NOTIFY_MEMBER_LEFT, v); }
    public boolean isNotifyNameChangedEnabled() { return b(KEY_NOTIFY_NAME_CHANGED, false); }
    public void setNotifyNameChangedEnabled(boolean v) { setB(KEY_NOTIFY_NAME_CHANGED, v); }
    public boolean isNotifyMemberRemovedEnabled() { return b(KEY_NOTIFY_MEMBER_REMOVED, false); }
    public void setNotifyMemberRemovedEnabled(boolean v) { setB(KEY_NOTIFY_MEMBER_REMOVED, v); }
    public boolean isNotifyGroupRenamedEnabled() { return b(KEY_NOTIFY_GROUP_RENAMED, false); }
    public void setNotifyGroupRenamedEnabled(boolean v) { setB(KEY_NOTIFY_GROUP_RENAMED, v); }
    public boolean isNotifyModeChangedEnabled() { return b(KEY_NOTIFY_MODE_CHANGED, true); }
    public void setNotifyModeChangedEnabled(boolean v) { setB(KEY_NOTIFY_MODE_CHANGED, v); }

    public GroupMode getGroupMode() { return e(KEY_GROUP_MODE, GroupMode.class, GroupMode.GROUP); }
    public void setGroupMode(GroupMode m) { setS(KEY_GROUP_MODE, m.name()); }
    public int getReplyWindowHours() { return i(KEY_REPLY_WINDOW_HOURS, 24); }
    public void setReplyWindowHours(int v) { setI(KEY_REPLY_WINDOW_HOURS, v); }
    public boolean isCopyRepliesToAdmins() { return b(KEY_COPY_REPLIES_TO_ADMINS, false); }
    public void setCopyRepliesToAdmins(boolean v) { setB(KEY_COPY_REPLIES_TO_ADMINS, v); }
    public boolean isBareKeywordsEnabled() { return b(KEY_BARE_KEYWORDS_ENABLED, true); }
    public void setBareKeywordsEnabled(boolean v) { setB(KEY_BARE_KEYWORDS_ENABLED, v); }

    public Set<SmsCommand> getDisabledCommands() { return SmsCommand.parseDisabled(prefs.getString(KEY_DISABLED_COMMANDS, "")); }
    public void setDisabledCommands(Set<SmsCommand> disabled) { setS(KEY_DISABLED_COMMANDS, SmsCommand.formatDisabled(disabled)); }
    public boolean isCommandEnabled(SmsCommand command) { return !getDisabledCommands().contains(command); }

    public int getCoalesceWindowSeconds() { return i(KEY_COALESCE_WINDOW_SECONDS, 45); }
    public void setCoalesceWindowSeconds(int v) { setI(KEY_COALESCE_WINDOW_SECONDS, v); }
    public boolean isCoalescingEnabled() { return getCoalesceWindowSeconds() > 0; }
    public int getMaxMergedSegments() { return i(KEY_MAX_MERGED_SEGMENTS, 3); }
    public void setMaxMergedSegments(int v) { setI(KEY_MAX_MERGED_SEGMENTS, v); }

    public JoinPolicy getJoinPolicy() { return e(KEY_JOIN_POLICY, JoinPolicy.class, JoinPolicy.OFF); }
    public void setJoinPolicy(JoinPolicy p) { setS(KEY_JOIN_POLICY, p.name()); }

    public long getPauseUntilMillis() { return l(KEY_PAUSE_UNTIL_MILLIS); }
    public void setPauseUntilMillis(long v) { setL(KEY_PAUSE_UNTIL_MILLIS, v); }

    public boolean isPaused() {
        long until = getPauseUntilMillis();
        if (until == 0) return false;
        return until == PAUSE_INDEFINITE || System.currentTimeMillis() < until;
    }

    public boolean isStopped() { return b(KEY_STOPPED, false); }
    // commit, not apply: Stop must survive a process death right after the tap.
    public void setStopped(boolean stopped) { prefs.edit().putBoolean(KEY_STOPPED, stopped).commit(); }

    public int getMaxMembers() { return i(KEY_MAX_MEMBERS, 0); }
    public void setMaxMembers(int v) { setI(KEY_MAX_MEMBERS, v); }

    public String getGroupFullMessage() {
        String stored = prefs.getString(KEY_GROUP_FULL_MESSAGE, null);
        return stored == null || stored.trim().isEmpty() ? appContext.getString(R.string.tpl_group_full_default) : stored;
    }

    public void setGroupFullMessage(String message) { setS(KEY_GROUP_FULL_MESSAGE, message); }

    // Must default to SMS: GROUP_MMS exposes members' numbers irreversibly.
    public DeliveryMode getDeliveryMode() { return e(KEY_DELIVERY_MODE, DeliveryMode.class, DeliveryMode.SMS); }

    public void setDeliveryMode(DeliveryMode mode) { setS(KEY_DELIVERY_MODE, mode.name()); }

    // 9 is the empirical carrier max (9 members + jRelay = 10 works, 11 fails). Do not raise without retesting.
    public int getSubgroupTargetSize() { return i(KEY_SUBGROUP_TARGET_SIZE, 9); }

    public long getMmsIngestSinceSeconds() { return l(KEY_MMS_INGEST_SINCE_SECONDS); }
    public void setMmsIngestSinceSeconds(long v) { setL(KEY_MMS_INGEST_SINCE_SECONDS, v); }
    public long getSmsCatchUpSinceMillis() { return l(KEY_SMS_CATCH_UP_SINCE_MILLIS); }
    public void setSmsCatchUpSinceMillis(long v) { setL(KEY_SMS_CATCH_UP_SINCE_MILLIS, v); }
}
