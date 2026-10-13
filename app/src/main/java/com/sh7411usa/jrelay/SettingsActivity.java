package com.sh7411usa.jrelay;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.sh7411usa.jrelay.db.DbHelper;
import com.sh7411usa.jrelay.db.MemberRepository;
import com.sh7411usa.jrelay.db.MessageRepository;
import com.sh7411usa.jrelay.db.OutboxRepository;
import com.sh7411usa.jrelay.model.Member;
import com.sh7411usa.jrelay.sms.CommandProcessor;
import com.sh7411usa.jrelay.sms.SmsCommand;
import com.sh7411usa.jrelay.sms.SmsSendService;
import com.sh7411usa.jrelay.sms.mms.MmsIngestService;
import com.sh7411usa.jrelay.util.DailyLimitManager;
import com.sh7411usa.jrelay.util.Prefs;
import com.sh7411usa.jrelay.util.RateLimitConfig;
import com.sh7411usa.jrelay.util.RateLimitSettings;
import com.sh7411usa.jrelay.util.UiUtil;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.IntConsumer;

public class SettingsActivity extends BaseActivity {

    private static final String[] LANGUAGE_CODES = {
            Prefs.LANGUAGE_SYSTEM, Prefs.LANGUAGE_ENGLISH, Prefs.LANGUAGE_HEBREW, Prefs.LANGUAGE_YIDDISH
    };
    private static final long[] PAUSE_DURATIONS_MS = {
            0L, 10_000L, 60_000L, 3_600_000L, 86_400_000L, Prefs.PAUSE_INDEFINITE
    };
    private static final String STATE_OPEN_CATEGORY = "open_category";
    private static final int[] CATEGORY_CONTAINER_IDS = {
            R.id.settings_cat_group, R.id.settings_cat_delivery, R.id.settings_cat_speed,
            R.id.settings_cat_limits, R.id.settings_cat_content, R.id.settings_cat_app
    };
    private static final int[] CATEGORY_ROW_IDS = {
            R.id.row_settings_cat_group, R.id.row_settings_cat_delivery, R.id.row_settings_cat_speed,
            R.id.row_settings_cat_limits, R.id.row_settings_cat_content, R.id.row_settings_cat_app
    };
    private static final int[] CATEGORY_TITLE_IDS = {
            R.string.settings_cat_group, R.string.settings_cat_delivery, R.string.settings_cat_speed,
            R.string.settings_cat_limits, R.string.settings_cat_content, R.string.settings_cat_app
    };

    private Prefs prefs;
    private MessageRepository messageRepository;
    private MemberRepository memberRepository;
    private Spinner pauseSpinner;
    private View stopRelayButton, startRelayButton, stopOverridesPauseText, resumeNowButton;
    private TextView pauseStatusText;
    private final Map<SmsCommand, CheckBox> commandSwitchBoxes = new EnumMap<>(SmsCommand.class);
    private int openCategory = -1;
    private Object categoryBackCallback;

    // Position 0 only ever arrives from a layout pass (e.g. recreation); treating it as resume
    // silently cancelled active pauses. Resuming is button_resume_now.
    private final AdapterView.OnItemSelectedListener pauseSpinnerListener = onSelect(position -> {
        if (position != 0) applyPauseSelection(position);
    });
    private final Handler pauseStatusHandler = new Handler(Looper.getMainLooper());
    private final Runnable pauseStatusTick = new Runnable() {
        @Override
        public void run() {
            refreshPauseStatus();
            pauseStatusHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        prefs = new Prefs(this);
        messageRepository = new MessageRepository(this);
        memberRepository = new MemberRepository(this);
        pauseSpinner = findViewById(R.id.spinner_pause);
        stopRelayButton = findViewById(R.id.button_stop_relay);
        startRelayButton = findViewById(R.id.button_start_relay);
        stopOverridesPauseText = findViewById(R.id.text_stop_overrides_pause);
        resumeNowButton = findViewById(R.id.button_resume_now);
        pauseStatusText = findViewById(R.id.text_pause_status);
        addCommandSwitches();
        // Length filters also apply to setText, so they must be set before populateFromPrefs().
        capDigits(RateLimitConfig.MAX_WAIT_SECONDS_CEILING, R.id.edit_min_wait, R.id.edit_max_wait);
        capDigits(RateLimitConfig.BURST_SIZE_CEILING, R.id.edit_burst_min, R.id.edit_burst_max, R.id.edit_fixed_burst_size);
        capDigits(RateLimitConfig.MICROSPACING_MS_CEILING, R.id.edit_microspacing_fixed_ms,
                R.id.edit_microspacing_min_ms, R.id.edit_microspacing_max_ms);
        populateFromPrefs();
        wireListeners();
        int restored = savedInstanceState != null ? savedInstanceState.getInt(STATE_OPEN_CATEGORY, -1) : -1;
        if (restored >= 0 && restored < CATEGORY_CONTAINER_IDS.length) showCategory(restored);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_OPEN_CATEGORY, openCategory);
    }

    // Not called on API 36 targets (predictive back); setCategoryBackCallback() covers those.
    @Override
    @SuppressWarnings("deprecation")
    @SuppressLint("GestureBackNavigation")
    public void onBackPressed() {
        if (openCategory >= 0) {
            showHub();
            return;
        }
        super.onBackPressed();
    }

    private void showCategory(int index) {
        openCategory = index;
        showIf(R.id.settings_hub, false);
        showIf(R.id.settings_pause_section, false);
        for (int i = 0; i < CATEGORY_CONTAINER_IDS.length; i++) showIf(CATEGORY_CONTAINER_IDS[i], i == index);
        ((TextView) findViewById(R.id.text_settings_title)).setText(CATEGORY_TITLE_IDS[index]);
        setCategoryBackCallback(true);
        final View container = findViewById(CATEGORY_CONTAINER_IDS[index]);
        final ScrollView scrollView = findViewById(R.id.settings_scroll);
        scrollView.post(() -> {
            scrollView.scrollTo(0, 0);
            // DPAD only: in touch mode focusing a lower EditText would scroll away from the top.
            if (!container.isInTouchMode()) container.requestFocus(View.FOCUS_DOWN);
        });
    }

    private void showHub() {
        final int left = openCategory;
        openCategory = -1;
        for (int id : CATEGORY_CONTAINER_IDS) showIf(id, false);
        showIf(R.id.settings_hub, true);
        showIf(R.id.settings_pause_section, true);
        ((TextView) findViewById(R.id.text_settings_title)).setText(R.string.rate_limit_title);
        setCategoryBackCallback(false);
        final ScrollView scrollView = findViewById(R.id.settings_scroll);
        scrollView.post(() -> {
            scrollView.scrollTo(0, 0);
            if (left >= 0) findViewById(CATEGORY_ROW_IDS[left]).requestFocus();
        });
    }

    private void setCategoryBackCallback(boolean register) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        OnBackInvokedDispatcher dispatcher = getOnBackInvokedDispatcher();
        if (register && categoryBackCallback == null) {
            OnBackInvokedCallback callback = this::showHub;
            dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
            categoryBackCallback = callback;
        } else if (!register && categoryBackCallback != null) {
            dispatcher.unregisterOnBackInvokedCallback((OnBackInvokedCallback) categoryBackCallback);
            categoryBackCallback = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadSystemSettings();
        updateClearHistoryButtonLabel();
        refreshGroupDailyLimitStatus();
        refreshCapacityStatus();
        pauseStatusHandler.post(pauseStatusTick);
        MmsIngestService.start(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        pauseStatusHandler.removeCallbacks(pauseStatusTick);
    }

    private void populateFromPrefs() {
        pauseSpinner.setSelection(0);
        refreshPauseStatus();
        select(R.id.spinner_group_mode, prefs.getGroupMode().ordinal());
        ((TextView) findViewById(R.id.text_app_version)).setText(getVersionLabel());
        setNumber(R.id.edit_reply_window_hours, prefs.getReplyWindowHours());
        check(R.id.checkbox_copy_replies_to_admins, prefs.isCopyRepliesToAdmins());
        check(R.id.checkbox_accept_bare_keywords, prefs.isBareKeywordsEnabled());
        Set<SmsCommand> disabledCommands = prefs.getDisabledCommands();
        for (Map.Entry<SmsCommand, CheckBox> e : commandSwitchBoxes.entrySet()) {
            e.getValue().setChecked(!disabledCommands.contains(e.getKey()));
        }
        check(R.id.checkbox_staggering_enabled, prefs.isStaggeringEnabled());
        setNumber(R.id.edit_min_wait, prefs.getMinWaitSeconds());
        setNumber(R.id.edit_max_wait, prefs.getMaxWaitSeconds());
        check(R.id.checkbox_initial_delay, prefs.isInitialDelayEnabled());
        select(R.id.spinner_burst_mode, prefs.getBurstMode().ordinal());
        setNumber(R.id.edit_burst_min, prefs.getBurstMin());
        setNumber(R.id.edit_burst_max, prefs.getBurstMax());
        setNumber(R.id.edit_fixed_burst_size, prefs.getFixedBurstSize());
        updateBurstModeVisibility(prefs.getBurstMode());
        check(R.id.checkbox_microspacing_enabled, prefs.isMicrospacingEnabled());
        select(R.id.spinner_microspacing_mode, prefs.getMicrospacingMode().ordinal());
        setNumber(R.id.edit_microspacing_fixed_ms, prefs.getMicrospacingFixedMs());
        setNumber(R.id.edit_microspacing_min_ms, prefs.getMicrospacingMinMs());
        setNumber(R.id.edit_microspacing_max_ms, prefs.getMicrospacingMaxMs());
        updateMicrospacingModeVisibility(prefs.getMicrospacingMode());
        setNumber(R.id.edit_coalesce_window_seconds, prefs.getCoalesceWindowSeconds());
        setNumber(R.id.edit_max_merged_segments, prefs.getMaxMergedSegments());
        check(R.id.checkbox_delivery_shuffle, prefs.isDeliveryShuffleEnabled());
        check(R.id.checkbox_group_delivery, prefs.getDeliveryMode() == Prefs.DeliveryMode.GROUP_MMS);
        check(R.id.checkbox_added_reporting_enabled, prefs.isAddedReportingEnabled());
        check(R.id.checkbox_notify_member_left, prefs.isNotifyMemberLeftEnabled());
        check(R.id.checkbox_notify_name_changed, prefs.isNotifyNameChangedEnabled());
        check(R.id.checkbox_notify_member_removed, prefs.isNotifyMemberRemovedEnabled());
        check(R.id.checkbox_notify_group_renamed, prefs.isNotifyGroupRenamedEnabled());
        check(R.id.checkbox_notify_mode_changed, prefs.isNotifyModeChangedEnabled());
        select(R.id.spinner_join_policy, prefs.getJoinPolicy().ordinal());
        setNumber(R.id.edit_max_members, prefs.getMaxMembers());
        ((EditText) findViewById(R.id.edit_group_full_message)).setText(prefs.getGroupFullMessage());
        refreshCapacityStatus();
        check(R.id.checkbox_append_sender_number, prefs.isAppendSenderNumberEnabled());
        check(R.id.checkbox_strip_phone_numbers, prefs.isStripPhoneNumbersEnabled());
        check(R.id.checkbox_salt_timestamp, prefs.isSaltTimestampEnabled());
        select(R.id.spinner_salt_timestamp_format, prefs.getSaltTimestampFormat().ordinal());
        check(R.id.checkbox_salt_hex, prefs.isSaltHexEnabled());
        select(R.id.spinner_salt_hex_position, prefs.getSaltHexPosition().ordinal());
        check(R.id.checkbox_salt_zwsp, prefs.isSaltZwspEnabled());
        setNumber(R.id.edit_retry_limit, prefs.getRetryLimit());
        setNumber(R.id.edit_failure_alert_threshold, prefs.getFailureAlertThreshold());
        ((EditText) findViewById(R.id.edit_daily_limit_reset_time)).setText(formatMinuteOfDay(prefs.getDailyLimitResetMinuteOfDay()));
        check(R.id.checkbox_group_daily_limit_enabled, prefs.isGroupDailyLimitEnabled());
        setNumber(R.id.edit_group_daily_limit_value, prefs.getGroupDailyLimitValue());
        setNumber(R.id.edit_default_individual_limit, prefs.getDefaultIndividualLimitSeed());
        select(R.id.spinner_language, Math.max(0, Arrays.asList(LANGUAGE_CODES).indexOf(prefs.getLanguageCode())));
        select(R.id.spinner_theme, prefs.getThemeChoice().ordinal());
    }

    private void wireListeners() {
        // Attach after the first layout pass so its automatic onItemSelected cannot cancel a pause.
        pauseSpinner.post(() -> pauseSpinner.setOnItemSelectedListener(pauseSpinnerListener));
        onClick(R.id.button_resume_now, v -> resumeNow());
        onClick(R.id.button_stop_relay, v -> confirmStopRelay());
        onClick(R.id.button_start_relay, v -> startRelay());
        onClick(R.id.button_save_group_mode, v -> saveGroupMode());
        onClick(R.id.button_save_reply_mode, v -> saveReplyModeSettings());
        onClick(R.id.button_save_commands, v -> saveCommandsSettings());
        onClick(R.id.button_save, v -> savePacingSettings());
        onClick(R.id.button_save_group_delivery, v -> saveGroupDelivery());
        onClick(R.id.button_save_delivery_shuffle, v -> {
            prefs.setDeliveryShuffleEnabled(checked(R.id.checkbox_delivery_shuffle));
            savedToast();
        });
        onClick(R.id.button_save_reporting, v -> {
            prefs.setJoinPolicy(Prefs.JoinPolicy.values()[selected(R.id.spinner_join_policy)]);
            savedToast();
        });
        onClick(R.id.button_save_notices, v -> saveNoticesSettings());
        onClick(R.id.button_save_capacity, v -> saveCapacitySettings());
        onClick(R.id.button_save_content, v -> saveContentSettings());
        onClick(R.id.button_save_failures, v -> saveFailureSettings());
        onClick(R.id.button_save_group_limit, v -> saveGroupLimitSettings());
        onClick(R.id.button_apply_to_all, v -> applyIndividualLimit(false));
        onClick(R.id.button_apply_to_non_customized, v -> applyIndividualLimit(true));
        onClick(R.id.button_system_save, v -> saveSystemSettings());
        onClick(R.id.button_clear_history, v -> confirmClearHistory());
        onClick(R.id.button_disband_group, v -> confirmDisbandGroup());
        onClick(R.id.button_view_license, v -> startActivity(new Intent(this, LicenseActivity.class)));
        onClick(R.id.text_system_permission_notice, v -> copyAdbCommandToClipboard());
        for (int i = 0; i < CATEGORY_ROW_IDS.length; i++) {
            final int index = i;
            onClick(CATEGORY_ROW_IDS[i], v -> showCategory(index));
        }
        spinner(R.id.spinner_burst_mode).setOnItemSelectedListener(onSelect(position ->
                updateBurstModeVisibility(Prefs.BurstMode.values()[position])));
        spinner(R.id.spinner_microspacing_mode).setOnItemSelectedListener(onSelect(position ->
                updateMicrospacingModeVisibility(Prefs.MicrospacingMode.values()[position])));
        spinner(R.id.spinner_language).setOnItemSelectedListener(onSelect(position -> {
            String newCode = LANGUAGE_CODES[position];
            if (!newCode.equals(prefs.getLanguageCode())) {
                prefs.setLanguageCode(newCode);
                restartApp();
            }
        }));
        spinner(R.id.spinner_theme).setOnItemSelectedListener(onSelect(position -> {
            Prefs.ThemeChoice newChoice = Prefs.ThemeChoice.values()[position];
            if (newChoice != prefs.getThemeChoice()) {
                prefs.setThemeChoice(newChoice);
                restartApp();
            }
        }));
    }

    private void applyPauseSelection(int position) {
        long duration = PAUSE_DURATIONS_MS[position];
        prefs.setPauseUntilMillis(duration == Prefs.PAUSE_INDEFINITE ? Prefs.PAUSE_INDEFINITE : System.currentTimeMillis() + duration);
        refreshPauseStatus();
        // Spring back to "Not Paused" without re-triggering the listener.
        pauseSpinner.setOnItemSelectedListener(null);
        pauseSpinner.setSelection(0);
        pauseSpinner.post(() -> pauseSpinner.setOnItemSelectedListener(pauseSpinnerListener));
    }

    private void resumeNow() {
        prefs.setPauseUntilMillis(0);
        SmsSendService.start(this);
        refreshPauseStatus();
    }

    private void confirmStopRelay() {
        int unsent = new OutboxRepository(this).countDiscardable();
        String message = getString(R.string.stop_relay_confirm);
        if (unsent > 0) message += " " + getString(R.string.stop_relay_confirm_discard, unsent);
        new AlertDialog.Builder(this)
                .setTitle(R.string.stop_relay_title)
                .setMessage(message)
                .setPositiveButton(R.string.action_stop_relay, (dialog, which) -> stopRelay())
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void stopRelay() {
        int discarded = new CommandProcessor(this).stopRelay();
        Toast.makeText(this, getString(R.string.toast_relay_stopped, discarded), Toast.LENGTH_LONG).show();
        onRelayToggled(R.id.button_start_relay);
    }

    private void startRelay() {
        new CommandProcessor(this).startRelay();
        onRelayToggled(R.id.button_stop_relay);
    }

    private void onRelayToggled(int replacementId) {
        refreshPauseStatus();
        // The pressed button just disappeared; keep DPAD focus on the one that replaced it.
        findViewById(replacementId).requestFocus();
    }

    private void refreshPauseStatus() {
        boolean stopped = prefs.isStopped();
        UiUtil.show(stopRelayButton, !stopped);
        UiUtil.show(startRelayButton, stopped);
        UiUtil.show(stopOverridesPauseText, stopped);
        pauseSpinner.setEnabled(!stopped);
        long until = prefs.getPauseUntilMillis();
        boolean paused = !(until == 0 || (until != Prefs.PAUSE_INDEFINITE && System.currentTimeMillis() >= until));
        UiUtil.show(resumeNowButton, paused && !stopped);
        TextView status = pauseStatusText;
        if (stopped) {
            status.setText(R.string.relay_status_stopped);
        } else if (!paused) {
            status.setText(R.string.pause_status_not_paused);
        } else if (until == Prefs.PAUSE_INDEFINITE) {
            status.setText(R.string.pause_status_indefinite);
        } else {
            long remainingMs = Math.max(0, until - System.currentTimeMillis());
            status.setText(getString(R.string.pause_status_timed, formatPauseDuration(remainingMs)));
        }
    }

    private String formatPauseDuration(long millis) {
        long totalSeconds = millis / 1000;
        if (totalSeconds < 60) return getString(R.string.duration_seconds, totalSeconds);
        long minutes = totalSeconds / 60;
        if (minutes < 60) return getString(R.string.duration_minutes_seconds, minutes, totalSeconds % 60);
        long hours = minutes / 60;
        if (hours < 24) return getString(R.string.duration_hours_minutes, hours, minutes % 60);
        return getString(R.string.duration_days_hours, hours / 24, hours % 24);
    }

    private void saveGroupMode() {
        Prefs.GroupMode newMode = Prefs.GroupMode.values()[selected(R.id.spinner_group_mode)];
        if (newMode != prefs.getGroupMode()) {
            new CommandProcessor(this).setGroupMode(newMode, getString(R.string.default_added_by_admin), -1);
        }
        savedToast();
    }

    private void saveReplyModeSettings() {
        prefs.setReplyWindowHours(Math.max(0, intOf(R.id.edit_reply_window_hours, prefs.getReplyWindowHours())));
        prefs.setCopyRepliesToAdmins(checked(R.id.checkbox_copy_replies_to_admins));
        savedToast();
    }

    private void saveCommandsSettings() {
        prefs.setBareKeywordsEnabled(checked(R.id.checkbox_accept_bare_keywords));
        Set<SmsCommand> disabled = EnumSet.noneOf(SmsCommand.class);
        for (Map.Entry<SmsCommand, CheckBox> e : commandSwitchBoxes.entrySet()) {
            if (!e.getValue().isChecked()) disabled.add(e.getKey());
        }
        prefs.setDisabledCommands(disabled);
        savedToast();
    }

    /** One heading per group plus a checkbox per command, skipping #join; always-on ones are greyed. */
    private void addCommandSwitches() {
        LinearLayout container = findViewById(R.id.container_command_switches);
        LayoutInflater inflater = getLayoutInflater();
        SmsCommand.Group group = null;
        for (SmsCommand c : SmsCommand.values()) {
            if (c.group == SmsCommand.Group.JOIN) continue;
            if (c.group != group) {
                group = c.group;
                TextView heading = new TextView(this);
                heading.setTextAppearance(R.style.TextAppearance_JRelay_Caption);
                heading.setText(group.headingRes);
                int top = container.getChildCount() == 0 ? 0 : UiUtil.dp(this, 8);
                heading.setPadding(0, top, 0, 0);
                container.addView(heading);
            }
            CheckBox box = (CheckBox) inflater.inflate(R.layout.row_command_switch, container, false);
            if (c.isAlwaysOn()) {
                box.setText(getString(R.string.command_always_on, getString(c.lineRes)));
                box.setChecked(true);
                box.setEnabled(false);
                box.setAlpha(0.5f);
            } else {
                box.setText(c.lineRes);
                commandSwitchBoxes.put(c, box);
            }
            container.addView(box);
        }
    }

    private String getVersionLabel() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            long code = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? info.getLongVersionCode() : info.versionCode;
            return getString(R.string.tpl_app_version, info.versionName, code);
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    private void updateBurstModeVisibility(Prefs.BurstMode mode) {
        showIf(R.id.container_burst_random, mode == Prefs.BurstMode.RANDOM_RANGE);
        showIf(R.id.container_burst_fixed, mode == Prefs.BurstMode.FIXED);
    }

    private void updateMicrospacingModeVisibility(Prefs.MicrospacingMode mode) {
        showIf(R.id.container_microspacing_fixed, mode == Prefs.MicrospacingMode.FIXED);
        showIf(R.id.container_microspacing_random, mode == Prefs.MicrospacingMode.RANDOM_RANGE);
    }

    private void savePacingSettings() {
        final int waitCap = RateLimitConfig.MAX_WAIT_SECONDS_CEILING;
        final int burstCap = RateLimitConfig.BURST_SIZE_CEILING;
        final int microCap = RateLimitConfig.MICROSPACING_MS_CEILING;
        prefs.setStaggeringEnabled(checked(R.id.checkbox_staggering_enabled));
        int min = intOf(R.id.edit_min_wait, prefs.getMinWaitSeconds());
        int max = intOf(R.id.edit_max_wait, prefs.getMaxWaitSeconds());
        prefs.setMinWaitSeconds(RateLimitConfig.clamp(min, 0, waitCap));
        prefs.setMaxWaitSeconds(RateLimitConfig.clamp(max, prefs.getMinWaitSeconds(), waitCap));
        prefs.setInitialDelayEnabled(checked(R.id.checkbox_initial_delay));
        prefs.setBurstMode(Prefs.BurstMode.values()[selected(R.id.spinner_burst_mode)]);
        int burstMin = intOf(R.id.edit_burst_min, prefs.getBurstMin());
        int burstMax = intOf(R.id.edit_burst_max, prefs.getBurstMax());
        prefs.setBurstMin(RateLimitConfig.clamp(burstMin, 1, burstCap));
        prefs.setBurstMax(RateLimitConfig.clamp(burstMax, prefs.getBurstMin(), burstCap));
        prefs.setFixedBurstSize(RateLimitConfig.clamp(intOf(R.id.edit_fixed_burst_size, prefs.getFixedBurstSize()), 1, burstCap));
        prefs.setMicrospacingEnabled(checked(R.id.checkbox_microspacing_enabled));
        prefs.setMicrospacingMode(Prefs.MicrospacingMode.values()[selected(R.id.spinner_microspacing_mode)]);
        prefs.setMicrospacingFixedMs(RateLimitConfig.clamp(intOf(R.id.edit_microspacing_fixed_ms, prefs.getMicrospacingFixedMs()), 0, microCap));
        int microMin = RateLimitConfig.clamp(intOf(R.id.edit_microspacing_min_ms, prefs.getMicrospacingMinMs()), 0, microCap);
        int microMax = intOf(R.id.edit_microspacing_max_ms, prefs.getMicrospacingMaxMs());
        prefs.setMicrospacingMinMs(microMin);
        prefs.setMicrospacingMaxMs(RateLimitConfig.clamp(microMax, microMin, microCap));
        prefs.setCoalesceWindowSeconds(Math.min(600, Math.max(0, intOf(R.id.edit_coalesce_window_seconds, prefs.getCoalesceWindowSeconds()))));
        prefs.setMaxMergedSegments(Math.max(1, intOf(R.id.edit_max_merged_segments, prefs.getMaxMergedSegments())));
        savedToast();
    }

    private void saveGroupDelivery() {
        CheckBox box = findViewById(R.id.checkbox_group_delivery);
        // With no sub-groups a GROUP_MMS post would reach nobody, so refuse and store SMS.
        boolean refused = box.isChecked() && memberRepository.getDistinctSubgroupIds().isEmpty();
        if (refused) box.setChecked(false);
        if (box.isChecked() && prefs.getDeliveryMode() != Prefs.DeliveryMode.GROUP_MMS) {
            // Group messages bypass staggering and salting, so warn before switching away from individual texts.
            new AlertDialog.Builder(this)
                    .setTitle(R.string.group_delivery_warning_title)
                    .setMessage(R.string.group_delivery_warning)
                    .setPositiveButton(R.string.group_delivery_warning_confirm, (dialog, which) -> applyGroupDelivery(false))
                    .setNegativeButton(R.string.action_cancel, (dialog, which) -> box.setChecked(false))
                    .setOnCancelListener(dialog -> box.setChecked(false))
                    .show();
            return;
        }
        applyGroupDelivery(refused);
    }

    private void applyGroupDelivery(boolean refused) {
        CheckBox box = findViewById(R.id.checkbox_group_delivery);
        Prefs.DeliveryMode previousMode = prefs.getDeliveryMode();
        prefs.setDeliveryMode(box.isChecked() ? Prefs.DeliveryMode.GROUP_MMS : Prefs.DeliveryMode.SMS);
        if (previousMode != Prefs.DeliveryMode.GROUP_MMS && prefs.getDeliveryMode() == Prefs.DeliveryMode.GROUP_MMS) {
            // Seed the ingest watermark to now so threads from before this moment are never bridged.
            prefs.setMmsIngestSinceSeconds(System.currentTimeMillis() / 1000L);
            MmsIngestService.start(this);
        }
        // On refusal show only the explanation; "Saved." would read as success.
        Toast.makeText(this, refused ? R.string.error_group_delivery_no_subgroups : R.string.rate_limit_member_saved,
                refused ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
    }

    private void saveNoticesSettings() {
        prefs.setAddedReportingEnabled(checked(R.id.checkbox_added_reporting_enabled));
        prefs.setNotifyMemberLeftEnabled(checked(R.id.checkbox_notify_member_left));
        prefs.setNotifyNameChangedEnabled(checked(R.id.checkbox_notify_name_changed));
        prefs.setNotifyMemberRemovedEnabled(checked(R.id.checkbox_notify_member_removed));
        prefs.setNotifyGroupRenamedEnabled(checked(R.id.checkbox_notify_group_renamed));
        prefs.setNotifyModeChangedEnabled(checked(R.id.checkbox_notify_mode_changed));
        toast(R.string.notices_saved);
    }

    private void saveCapacitySettings() {
        int maxMembers = Math.max(0, intOf(R.id.edit_max_members, prefs.getMaxMembers()));
        prefs.setMaxMembers(maxMembers);
        setNumber(R.id.edit_max_members, maxMembers);
        prefs.setGroupFullMessage(((EditText) findViewById(R.id.edit_group_full_message)).getText().toString().trim());
        refreshCapacityStatus();
        toast(R.string.capacity_saved);
    }

    private void refreshCapacityStatus() {
        ((TextView) findViewById(R.id.text_capacity_status))
                .setText(getString(R.string.capacity_status, memberRepository.countActiveMembers()));
    }

    private void saveContentSettings() {
        prefs.setAppendSenderNumberEnabled(checked(R.id.checkbox_append_sender_number));
        prefs.setStripPhoneNumbersEnabled(checked(R.id.checkbox_strip_phone_numbers));
        prefs.setSaltTimestampEnabled(checked(R.id.checkbox_salt_timestamp));
        prefs.setSaltTimestampFormat(Prefs.SaltTimestampFormat.values()[selected(R.id.spinner_salt_timestamp_format)]);
        prefs.setSaltHexEnabled(checked(R.id.checkbox_salt_hex));
        prefs.setSaltHexPosition(Prefs.SaltHexPosition.values()[selected(R.id.spinner_salt_hex_position)]);
        prefs.setSaltZwspEnabled(checked(R.id.checkbox_salt_zwsp));
        savedToast();
    }

    private void saveFailureSettings() {
        prefs.setRetryLimit(Math.max(0, intOf(R.id.edit_retry_limit, prefs.getRetryLimit())));
        prefs.setFailureAlertThreshold(Math.max(1, intOf(R.id.edit_failure_alert_threshold, prefs.getFailureAlertThreshold())));
        savedToast();
    }

    private void saveGroupLimitSettings() {
        EditText resetInput = findViewById(R.id.edit_daily_limit_reset_time);
        Integer resetMinute = parseMinuteOfDay(resetInput.getText().toString().trim());
        prefs.setDailyLimitResetMinuteOfDay(resetMinute != null ? resetMinute : prefs.getDailyLimitResetMinuteOfDay());
        resetInput.setText(formatMinuteOfDay(prefs.getDailyLimitResetMinuteOfDay()));
        prefs.setGroupDailyLimitEnabled(checked(R.id.checkbox_group_daily_limit_enabled));
        prefs.setGroupDailyLimitValue(Math.max(0, intOf(R.id.edit_group_daily_limit_value, prefs.getGroupDailyLimitValue())));
        prefs.setDefaultIndividualLimitSeed(Math.max(0, intOf(R.id.edit_default_individual_limit, prefs.getDefaultIndividualLimitSeed())));
        refreshGroupDailyLimitStatus();
        savedToast();
    }

    private void applyIndividualLimit(boolean onlyNonCustomized) {
        int value = Math.max(0, intOf(R.id.edit_default_individual_limit, prefs.getDefaultIndividualLimitSeed()));
        prefs.setDefaultIndividualLimitSeed(value);
        int affected = 0;
        for (Member m : memberRepository.getActiveMembers()) {
            if (!onlyNonCustomized || !m.dailyLimitCustom) affected++;
        }
        new DailyLimitManager(this).bulkSetIndividualLimit(value, onlyNonCustomized);
        Toast.makeText(this, getString(R.string.individual_limit_applied, affected), Toast.LENGTH_SHORT).show();
    }

    private void refreshGroupDailyLimitStatus() {
        TextView view = findViewById(R.id.text_group_daily_limit_status);
        DailyLimitManager.Status status = new DailyLimitManager(this).groupStatus();
        if (!status.enabled) {
            view.setText(R.string.group_daily_limit_status_disabled);
            return;
        }
        String resetLabel = DateFormat.format("h:mm a", status.resetAtMillis).toString();
        view.setText(getString(R.string.tpl_group_daily_limit_status, status.used, status.limit, resetLabel));
    }

    private String formatMinuteOfDay(int minuteOfDay) {
        return String.format(Locale.US, "%02d:%02d", minuteOfDay / 60, minuteOfDay % 60);
    }

    private Integer parseMinuteOfDay(String text) {
        String[] parts = text.split(":");
        if (parts.length != 2) return null;
        try {
            int hour = Integer.parseInt(parts[0].trim());
            int minute = Integer.parseInt(parts[1].trim());
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return null;
            return hour * 60 + minute;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void restartApp() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private void loadSystemSettings() {
        boolean hasPermission = RateLimitSettings.hasPermission(this);
        int maxCount = RateLimitSettings.readMaxCount(this);
        long intervalMs = RateLimitSettings.readIntervalMs(this);
        String unavailable = getString(R.string.rate_limit_value_unavailable);
        EditText maxCountInput = findViewById(R.id.edit_system_max_count);
        EditText intervalInput = findViewById(R.id.edit_system_interval);
        maxCountInput.setText(maxCount >= 0 ? String.valueOf(maxCount) : "");
        intervalInput.setText(intervalMs >= 0 ? String.valueOf(intervalMs / 60000L) : "");
        maxCountInput.setHint(maxCount >= 0 ? "" : unavailable);
        intervalInput.setHint(intervalMs >= 0 ? "" : unavailable);
        maxCountInput.setEnabled(hasPermission);
        intervalInput.setEnabled(hasPermission);
        findViewById(R.id.button_system_save).setEnabled(hasPermission);
        showIf(R.id.text_system_permission_notice, !hasPermission);
        ((TextView) findViewById(R.id.text_system_permission_notice)).setText(getString(R.string.line_break_join,
                getString(R.string.rate_limit_permission_missing), RateLimitSettings.ADB_GRANT_COMMAND));
    }

    private void copyAdbCommandToClipboard() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("adb command", RateLimitSettings.ADB_GRANT_COMMAND));
        toast(R.string.toast_adb_command_copied);
    }

    private void saveSystemSettings() {
        int maxCount = intOf(R.id.edit_system_max_count, -1);
        int intervalMinutes = intOf(R.id.edit_system_interval, -1);
        if (maxCount >= 0) RateLimitSettings.writeMaxCount(this, maxCount);
        if (intervalMinutes >= 0) RateLimitSettings.writeIntervalMs(this, intervalMinutes * 60000L);
        loadSystemSettings();
    }

    private void updateClearHistoryButtonLabel() {
        long bytes = messageRepository.estimateStorageBytes();
        String size = bytes < 1024 ? "~" + bytes + " B"
                : bytes < 1024 * 1024 ? "~" + (bytes / 1024) + " KB"
                : String.format(Locale.US, "~%.1f MB", bytes / (1024.0 * 1024.0));
        ((TextView) findViewById(R.id.button_clear_history)).setText(getString(R.string.action_clear_history, size));
    }

    private void confirmClearHistory() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.clear_history_title)
                .setMessage(R.string.clear_history_warning)
                .setPositiveButton(R.string.action_continue, (dialog, which) -> {
                    messageRepository.deleteAll();
                    Toast.makeText(this, R.string.clear_history_done, Toast.LENGTH_LONG).show();
                    updateClearHistoryButtonLabel();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void confirmDisbandGroup() {
        int pin = 1000 + new Random().nextInt(9000);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_disband_confirm, null);
        EditText pinInput = dialogView.findViewById(R.id.edit_disband_pin);
        ((TextView) dialogView.findViewById(R.id.text_disband_pin)).setText(String.valueOf(pin));
        new AlertDialog.Builder(this)
                .setTitle(R.string.disband_group_title)
                .setMessage(R.string.disband_group_warning)
                .setView(dialogView)
                .setPositiveButton(R.string.action_disband_group, (dialog, which) -> {
                    if (!pinInput.getText().toString().trim().equals(String.valueOf(pin))) {
                        Toast.makeText(this, R.string.disband_pin_mismatch, Toast.LENGTH_LONG).show();
                        return;
                    }
                    DbHelper.getInstance(this).wipeAllData();
                    prefs.resetGroupName();
                    Toast.makeText(this, R.string.disband_group_done, Toast.LENGTH_LONG).show();
                    restartApp();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private static AdapterView.OnItemSelectedListener onSelect(IntConsumer onPosition) {
        return new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                onPosition.accept(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        };
    }

    private void capDigits(int ceiling, int... ids) {
        for (int id : ids) {
            ((EditText) findViewById(id)).setFilters(new InputFilter[]{new InputFilter.LengthFilter(String.valueOf(ceiling).length())});
        }
    }

    private void onClick(int id, View.OnClickListener listener) {
        findViewById(id).setOnClickListener(listener);
    }

    private void showIf(int id, boolean show) {
        UiUtil.show(findViewById(id), show);
    }

    private Spinner spinner(int id) {
        return findViewById(id);
    }

    private void select(int id, int position) {
        spinner(id).setSelection(position);
    }

    private int selected(int id) {
        return spinner(id).getSelectedItemPosition();
    }

    private void check(int id, boolean on) {
        ((CheckBox) findViewById(id)).setChecked(on);
    }

    private boolean checked(int id) {
        return ((CheckBox) findViewById(id)).isChecked();
    }

    private void setNumber(int id, int value) {
        ((EditText) findViewById(id)).setText(String.valueOf(value));
    }

    private int intOf(int id, int defaultValue) {
        try {
            return Integer.parseInt(((EditText) findViewById(id)).getText().toString().trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private void toast(int resId) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show();
    }

    private void savedToast() {
        toast(R.string.rate_limit_member_saved);
    }
}
