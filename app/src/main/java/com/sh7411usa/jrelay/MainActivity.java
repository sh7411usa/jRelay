package com.sh7411usa.jrelay;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import com.sh7411usa.jrelay.db.JoinRequestRepository;
import com.sh7411usa.jrelay.db.MemberRepository;
import com.sh7411usa.jrelay.db.MessageRepository;
import com.sh7411usa.jrelay.db.OutboxRepository;
import com.sh7411usa.jrelay.model.Member;
import com.sh7411usa.jrelay.model.MessageRecord;
import com.sh7411usa.jrelay.sms.CommandProcessor;
import com.sh7411usa.jrelay.sms.SendQueueStatus;
import com.sh7411usa.jrelay.sms.SmsCatchUp;
import com.sh7411usa.jrelay.sms.SmsSendService;
import com.sh7411usa.jrelay.sms.Watchdog;
import com.sh7411usa.jrelay.sms.mms.MmsIngestService;
import com.sh7411usa.jrelay.util.DailyLimitManager;
import com.sh7411usa.jrelay.util.Prefs;
import com.sh7411usa.jrelay.util.UiUtil;

import static com.sh7411usa.jrelay.util.UiUtil.show;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends BaseActivity {

    private static final long QUEUE_STATUS_TICK_MS = 1000;
    // MmsIngestService starts asynchronously, so isRunning() is false right after start().
    private static final long SERVICE_START_GRACE_MS = 1500;
    private static final String[] CRITICAL_PERMISSIONS = {
            Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS};
    // Only required while group delivery is on.
    private static final String[] GROUP_MMS_PERMISSIONS = {
            Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_MMS};

    private Prefs prefs;
    private MemberRepository memberRepository;
    private MessageRepository messageRepository;
    private OutboxRepository outboxRepository;
    private CommandProcessor commandProcessor;

    private TextView groupNameView, announcementBadgeView, replyBadgeView, pausedBadgeView,
            stoppedBadgeView, relayStoppedBannerView, statsMembersView, statsAdminsView,
            statsMutedView, statsMessagesTodayView, statsMessagesTotalView, statsFailedTodayView,
            queueCountView, sendingBadgeView, nextBurstView, permissionWarningView,
            joinRequestsBannerView, forwardingHealthView, batteryBannerView, recentActivityEmptyView;
    private LinearLayout recentActivityContainer;
    private long serviceStartRequestedAt;
    private boolean restartFailed;
    // Recent-activity rows by record index; null where the row is not clickable.
    private final List<View> recentActivityRows = new ArrayList<>();
    private int recentActivityFocusIndex = -1;

    private final Handler queueStatusHandler = new Handler(Looper.getMainLooper());
    private final Runnable queueStatusTick = new Runnable() {
        @Override
        public void run() {
            refreshQueueStatus();
            updateForwardingHealthBanner();
            queueStatusHandler.postDelayed(this, QUEUE_STATUS_TICK_MS);
        }
    };
    private final Runnable restartCheck = () -> {
        restartFailed = !MmsIngestService.isRunning();
        updateForwardingHealthBanner();
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        if (!prefs.isConsentAccepted()) {
            startActivity(new Intent(this, ConsentActivity.class));
            finish();
            return;
        }
        setContentView(R.layout.activity_main);
        memberRepository = new MemberRepository(this);
        messageRepository = new MessageRepository(this);
        outboxRepository = new OutboxRepository(this);
        commandProcessor = new CommandProcessor(this);

        groupNameView = findViewById(R.id.text_group_name);
        announcementBadgeView = findViewById(R.id.badge_announcement_mode);
        replyBadgeView = findViewById(R.id.badge_reply_mode);
        pausedBadgeView = findViewById(R.id.badge_service_paused);
        stoppedBadgeView = findViewById(R.id.badge_service_stopped);
        relayStoppedBannerView = findViewById(R.id.banner_relay_stopped);
        statsMembersView = findViewById(R.id.text_stats_members);
        statsAdminsView = findViewById(R.id.text_stats_admins);
        statsMutedView = findViewById(R.id.text_stats_muted);
        statsMessagesTodayView = findViewById(R.id.text_stats_messages_today);
        statsMessagesTotalView = findViewById(R.id.text_stats_messages_total);
        statsFailedTodayView = findViewById(R.id.text_stats_failed_today);
        queueCountView = findViewById(R.id.text_queue_count);
        sendingBadgeView = findViewById(R.id.badge_sending);
        nextBurstView = findViewById(R.id.text_next_burst);
        permissionWarningView = findViewById(R.id.warning_permission_missing);
        joinRequestsBannerView = findViewById(R.id.banner_join_requests);
        forwardingHealthView = findViewById(R.id.banner_forwarding_health);
        batteryBannerView = findViewById(R.id.banner_battery_optimisation);
        recentActivityContainer = findViewById(R.id.container_recent_activity);
        recentActivityEmptyView = findViewById(R.id.text_recent_activity_empty);
        int widthDp = getResources().getConfiguration().screenWidthDp;
        int columns = widthDp >= 360 ? 3 : widthDp >= 200 ? 2 : 1;
        ((GridLayout) findViewById(R.id.grid_stats_group)).setColumnCount(columns);
        ((GridLayout) findViewById(R.id.grid_stats_messages)).setColumnCount(columns);

        findViewById(R.id.button_options).setOnClickListener(this::showOptionsMenu);
        findViewById(R.id.button_membership).setOnClickListener(v -> open(MembershipActivity.class));
        permissionWarningView.setOnClickListener(v -> startActivity(
                withPackage(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS))));
        relayStoppedBannerView.setOnClickListener(v -> open(SettingsActivity.class));
        joinRequestsBannerView.setOnClickListener(v -> open(MembershipActivity.class));
        forwardingHealthView.setOnClickListener(v -> onForwardingHealthClicked());
        batteryBannerView.setOnClickListener(v -> requestBatteryExemption());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!prefs.isConsentAccepted()) return;
        boolean missing = anyDenied(CRITICAL_PERMISSIONS)
                || (prefs.getDeliveryMode() == Prefs.DeliveryMode.GROUP_MMS && anyDenied(GROUP_MMS_PERMISSIONS));
        show(permissionWarningView, missing);
        int pending = new JoinRequestRepository(this).count();
        if (pending > 0) joinRequestsBannerView.setText(getString(R.string.banner_join_requests, pending));
        show(joinRequestsBannerView, pending > 0);
        PowerManager pm = getSystemService(PowerManager.class);
        show(batteryBannerView, !(pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())));
        if (outboxRepository.countUnsent() > 0) SmsSendService.start(this);
        requestIngestStart();
        Watchdog.schedule(this);
        // The relay's own number must never be a member; undo a mistaken add on every resume.
        String purgedName = new CommandProcessor(this).purgeRelaySelfMember();
        if (purgedName != null) {
            Toast.makeText(this, getString(R.string.toast_relay_self_member_removed, purgedName),
                    Toast.LENGTH_LONG).show();
        }
        SmsCatchUp.runAsync(this);
        updateForwardingHealthBanner();
        refresh();
        queueStatusHandler.post(queueStatusTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        queueStatusHandler.removeCallbacks(queueStatusTick);
        queueStatusHandler.removeCallbacks(restartCheck);
    }

    private void open(Class<?> cls) {
        startActivity(new Intent(this, cls));
    }

    private Intent withPackage(Intent intent) {
        return intent.setData(Uri.fromParts("package", getPackageName(), null));
    }

    private boolean anyDenied(String[] permissions) {
        for (String p : permissions) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return true;
        }
        return false;
    }

    private void requestIngestStart() {
        serviceStartRequestedAt = System.currentTimeMillis();
        MmsIngestService.start(this);
    }

    private void updateForwardingHealthBanner() {
        int textRes = 0;
        // Individual texts are the default; only a stalled group delivery needs a banner.
        if (prefs.getDeliveryMode() != Prefs.DeliveryMode.GROUP_MMS) {
            restartFailed = false;
        } else if (MmsIngestService.isRunning()) {
            restartFailed = false;
        } else if (System.currentTimeMillis() - serviceStartRequestedAt >= SERVICE_START_GRACE_MS) {
            textRes = restartFailed ? R.string.banner_forwarding_stopped_battery : R.string.banner_forwarding_stopped;
        } else {
            return; // still inside the start grace period
        }
        if (textRes != 0) forwardingHealthView.setText(textRes);
        show(forwardingHealthView, textRes != 0);
    }

    private void onForwardingHealthClicked() {
        if (prefs.getDeliveryMode() != Prefs.DeliveryMode.GROUP_MMS) return;
        requestIngestStart();
        queueStatusHandler.removeCallbacks(restartCheck);
        queueStatusHandler.postDelayed(restartCheck, SERVICE_START_GRACE_MS);
    }

    private void requestBatteryExemption() {
        try {
            startActivity(withPackage(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
            }
        }
    }

    private void showOptionsMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenuInflater().inflate(R.menu.main_options_menu, popup.getMenu());
        // Offer the two modes that are not currently active.
        Prefs.GroupMode[] targets = new Prefs.GroupMode[2];
        Prefs.GroupMode current = prefs.getGroupMode();
        int n = 0;
        for (Prefs.GroupMode mode : Prefs.GroupMode.values()) {
            if (mode != current && n < 2) targets[n++] = mode;
        }
        int[] modeItems = {R.id.menu_switch_mode_a, R.id.menu_switch_mode_b};
        for (int i = 0; i < 2; i++) {
            Prefs.GroupMode m = targets[i];
            popup.getMenu().findItem(modeItems[i]).setTitle(m == Prefs.GroupMode.GROUP ? R.string.menu_switch_to_group
                    : m == Prefs.GroupMode.ANNOUNCEMENT ? R.string.menu_switch_to_announcement
                    : R.string.menu_switch_to_reply);
        }
        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.menu_set_group_name) showRenameDialog();
            else if (id == R.id.menu_add_member) open(AddMemberActivity.class);
            else if (id == R.id.menu_settings) open(SettingsActivity.class);
            else if (id == R.id.menu_send_to_group) open(SendGroupMessageActivity.class);
            else if (id == R.id.menu_switch_mode_a || id == R.id.menu_switch_mode_b) {
                commandProcessor.setGroupMode(targets[id == R.id.menu_switch_mode_a ? 0 : 1],
                        getString(R.string.default_added_by_admin), -1);
                refreshQueueStatus();
            } else return false;
            return true;
        });
        popup.show();
    }

    private void refresh() {
        List<Member> members = memberRepository.getActiveMembers();
        int adminCount = 0, mutedCount = 0;
        for (Member m : members) {
            if (m.isAdmin) adminCount++;
            if (m.isMuted) mutedCount++;
        }
        statsMembersView.setText(String.valueOf(members.size()));
        statsAdminsView.setText(String.valueOf(adminCount));
        statsMutedView.setText(String.valueOf(mutedCount));
        long windowStart = new DailyLimitManager(this).currentWindowStart();
        statsMessagesTodayView.setText(String.valueOf(messageRepository.countSince(windowStart)));
        statsMessagesTotalView.setText(String.valueOf(messageRepository.countAll()));
        statsFailedTodayView.setText(String.valueOf(messageRepository.countFailedSince(windowStart)));
        refreshQueueStatus();
        renderRecentActivity();
    }

    private void refreshQueueStatus() {
        groupNameView.setText(prefs.getGroupName());
        Prefs.GroupMode mode = prefs.getGroupMode();
        show(announcementBadgeView, mode == Prefs.GroupMode.ANNOUNCEMENT);
        show(replyBadgeView, mode == Prefs.GroupMode.REPLY);
        show(pausedBadgeView, prefs.isPaused());
        boolean stopped = prefs.isStopped();
        show(stoppedBadgeView, stopped);
        show(relayStoppedBannerView, stopped);
        queueCountView.setText(String.valueOf(outboxRepository.countUnsent()));

        long nextBurstAt = SendQueueStatus.getNextBurstAtMillis();
        int holding = outboxRepository.countHolding();
        if (nextBurstAt > 0) {
            long remainingMs = Math.max(0, nextBurstAt - System.currentTimeMillis());
            String burstStatus = getString(R.string.stats_next_burst,
                    formatDuration(remainingMs), SendQueueStatus.getNextBurstSize());
            if (holding > 0) burstStatus += getString(R.string.queue_holding_suffix, holding);
            nextBurstView.setText(burstStatus);
        } else if (holding > 0) {
            nextBurstView.setText(getString(R.string.queue_holding_only, holding));
        }
        show(nextBurstView, nextBurstAt > 0 || holding > 0);
        show(sendingBadgeView, nextBurstAt > 0);
    }

    private String formatDuration(long millis) {
        long minutes = millis / 60000, seconds = (millis / 1000) % 60;
        return minutes > 0 ? getString(R.string.dashboard_duration_ms, minutes, seconds)
                : getString(R.string.dashboard_duration_s, seconds);
    }

    private void renderRecentActivity() {
        int focused = recentActivityRows.indexOf(recentActivityContainer.getFocusedChild());
        if (focused >= 0) recentActivityFocusIndex = focused;
        recentActivityRows.clear();
        recentActivityContainer.removeAllViews();
        List<MessageRecord> recent = messageRepository.getRecent(20);
        show(recentActivityEmptyView, recent.isEmpty());
        if (recent.isEmpty()) return;
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < recent.size(); i++) {
            MessageRecord record = recent.get(i);
            View row = inflater.inflate(R.layout.row_message, recentActivityContainer, false);
            ((TextView) row.findViewById(R.id.text_message_body)).setText(record.body);
            String date = DateUtils.formatDateTime(this, record.timestamp,
                    DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME
                            | DateUtils.FORMAT_ABBREV_MONTH | DateUtils.FORMAT_NO_YEAR);
            Member m = record.memberId != null ? memberRepository.findById(record.memberId) : null;
            ((TextView) row.findViewById(R.id.text_message_meta)).setText(
                    m != null ? getString(R.string.dashboard_activity_meta, m.nickname, date) : date);
            View focusableRow = null;
            if (record.memberId != null) {
                long memberId = record.memberId;
                final int index = i;
                focusableRow = row;
                row.setClickable(true);
                row.setFocusable(true);
                row.setBackgroundResource(R.drawable.focus_highlight);
                row.setOnClickListener(v -> {
                    recentActivityFocusIndex = index;
                    startActivity(new Intent(this, MemberDetailActivity.class)
                            .putExtra(MemberDetailActivity.EXTRA_MEMBER_ID, memberId));
                });
            }
            recentActivityRows.add(focusableRow);
            recentActivityContainer.addView(row);
            if (i < recent.size() - 1) recentActivityContainer.addView(UiUtil.createDivider(this, R.color.divider));
        }
        restoreRecentActivityFocus();
    }

    // Refocuses the remembered row, or the nearest clickable one. DPAD only.
    private void restoreRecentActivityFocus() {
        int target = recentActivityFocusIndex;
        recentActivityFocusIndex = -1;
        int size = recentActivityRows.size();
        if (target < 0 || size == 0 || recentActivityContainer.isInTouchMode()) return;
        target = Math.min(target, size - 1);
        for (int offset = 0; offset < size; offset++) {
            for (int c : new int[] {target + offset, target - offset}) {
                if (c >= 0 && c < size && recentActivityRows.get(c) != null) {
                    recentActivityRows.get(c).requestFocus();
                    return;
                }
            }
        }
    }

    private void showRenameDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_text_input, null);
        EditText input = dialogView.findViewById(R.id.edit_text_input);
        input.setText(prefs.getGroupName());
        new AlertDialog.Builder(this)
                .setTitle(R.string.edit_group_name)
                .setView(dialogView)
                .setPositiveButton(R.string.action_save, (dialog, which) -> {
                    String newName = input.getText().toString().trim();
                    if (!newName.isEmpty() && !newName.equals(prefs.getGroupName())) {
                        commandProcessor.changeGroupName(newName, getString(R.string.default_added_by_admin), -1);
                        refresh();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }
}
