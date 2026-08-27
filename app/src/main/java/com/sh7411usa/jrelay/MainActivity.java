package com.sh7411usa.jrelay;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.sh7411usa.jrelay.db.MemberRepository;
import com.sh7411usa.jrelay.db.MessageRepository;
import com.sh7411usa.jrelay.db.OutboxRepository;
import com.sh7411usa.jrelay.model.Member;
import com.sh7411usa.jrelay.model.MessageRecord;
import com.sh7411usa.jrelay.sms.CommandProcessor;
import com.sh7411usa.jrelay.sms.SendQueueStatus;
import com.sh7411usa.jrelay.util.Prefs;
import com.sh7411usa.jrelay.util.UiUtil;
import com.sh7411usa.jrelay.util.MessageDetailsDialog;

import java.util.List;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {

    private static final long QUEUE_STATUS_TICK_MS = 1000;

    private Prefs prefs;
    private MemberRepository memberRepository;
    private MessageRepository messageRepository;
    private OutboxRepository outboxRepository;
    private CommandProcessor commandProcessor;

    private TextView groupNameView;
    private TextView statsMembersView;
    private TextView statsAdminsView;
    private TextView statsMutedView;
    private TextView statsMessagesTodayView;
    private TextView statsMessagesTotalView;
    private TextView queueCountView;
    private TextView deliverySummaryView;
    private TextView nextBurstView;
    private LinearLayout recentActivityContainer;

    private final Handler queueStatusHandler = new Handler(Looper.getMainLooper());
    private final Runnable queueStatusTick = new Runnable() {
        @Override
        public void run() {
            refreshQueueStatus();
            renderRecentActivity();
            queueStatusHandler.postDelayed(this, QUEUE_STATUS_TICK_MS);
        }
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
        statsMembersView = findViewById(R.id.text_stats_members);
        statsAdminsView = findViewById(R.id.text_stats_admins);
        statsMutedView = findViewById(R.id.text_stats_muted);
        statsMessagesTodayView = findViewById(R.id.text_stats_messages_today);
        statsMessagesTotalView = findViewById(R.id.text_stats_messages_total);
        queueCountView = findViewById(R.id.text_queue_count);
        deliverySummaryView = findViewById(R.id.text_delivery_summary);
        nextBurstView = findViewById(R.id.text_next_burst);
        recentActivityContainer = findViewById(R.id.container_recent_activity);

        findViewById(R.id.button_edit_group_name).setOnClickListener(v -> showRenameDialog());
        findViewById(R.id.button_add_member).setOnClickListener(v ->
                startActivity(new Intent(this, AddMemberActivity.class)));
        findViewById(R.id.button_membership).setOnClickListener(v ->
                startActivity(new Intent(this, MembershipActivity.class)));
        findViewById(R.id.button_rate_limit).setOnClickListener(v ->
                startActivity(new Intent(this, RateLimitActivity.class)));
        findViewById(R.id.button_send_group_message).setOnClickListener(v ->
                startActivity(new Intent(this, SendGroupMessageActivity.class)));
        findViewById(R.id.button_failed_messages).setOnClickListener(v ->
                startActivity(new Intent(this, FailedMessagesActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!prefs.isConsentAccepted()) {
            return;
        }
        refresh();
        queueStatusHandler.post(queueStatusTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        queueStatusHandler.removeCallbacks(queueStatusTick);
    }

    private void refresh() {
        List<Member> members = memberRepository.getActiveMembers();
        int adminCount = 0;
        int mutedCount = 0;
        for (Member m : members) {
            if (m.isAdmin) {
                adminCount++;
            }
            if (m.isMuted) {
                mutedCount++;
            }
        }
        statsMembersView.setText(getString(R.string.stats_members, members.size()));
        statsAdminsView.setText(getString(R.string.stats_admins, adminCount));
        statsMutedView.setText(getString(R.string.stats_muted, mutedCount));

        long startOfDay = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1);
        statsMessagesTodayView.setText(getString(R.string.stats_messages_today, messageRepository.countSince(startOfDay)));
        statsMessagesTotalView.setText(getString(R.string.stats_messages_total, messageRepository.countAll()));

        refreshQueueStatus();
        renderRecentActivity();
    }

    private void refreshQueueStatus() {
        groupNameView.setText(prefs.getGroupName());
        queueCountView.setText(getString(R.string.stats_queue_count, outboxRepository.countUnsent()));
        deliverySummaryView.setText(getString(R.string.stats_delivery_summary,
                outboxRepository.countByStatus("DELIVERED"),
                outboxRepository.countByStatus("SENT"),
                outboxRepository.countByStatus("FAILED", "DELIVERY_FAILED")));

        long nextBurstAt = SendQueueStatus.getNextBurstAtMillis();
        if (nextBurstAt > 0) {
            long remainingMs = Math.max(0, nextBurstAt - System.currentTimeMillis());
            nextBurstView.setText(getString(R.string.stats_next_burst,
                    formatDuration(remainingMs), SendQueueStatus.getNextBurstSize()));
            nextBurstView.setVisibility(View.VISIBLE);
        } else {
            nextBurstView.setVisibility(View.GONE);
        }
    }

    private String formatDuration(long millis) {
        long totalSeconds = millis / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        if (minutes > 0) {
            return minutes + "m " + seconds + "s";
        }
        return seconds + "s";
    }

    private void renderRecentActivity() {
        recentActivityContainer.removeAllViews();
        List<MessageRecord> recent = messageRepository.getRecent(20);
        if (recent.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.no_activity_yet);
            recentActivityContainer.addView(empty);
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < recent.size(); i++) {
            MessageRecord record = recent.get(i);
            View row = inflater.inflate(R.layout.row_message, recentActivityContainer, false);
            TextView bodyView = row.findViewById(R.id.text_message_body);
            TextView metaView = row.findViewById(R.id.text_message_meta);
            bodyView.setText(record.body);
            String memberLabel = "";
            Member rowMember = null;
            if (record.memberId != null) {
                rowMember = memberRepository.findById(record.memberId);
                if (rowMember != null) {
                    memberLabel = rowMember.nickname + " • ";
                }
            }
            String meta = memberLabel + DateFormat.format("MMM d, h:mm a", record.timestamp);
            String deliveryStatus = UiUtil.deliveryStatusLabel(this,
                    record.deliveryStatus, record.deliveryErrorCode);
            if (deliveryStatus != null) {
                meta += " • " + deliveryStatus;
            }
            metaView.setText(meta);
            Member detailsMember = rowMember;
            row.setLongClickable(true);
            row.setOnLongClickListener(v -> {
                MessageDetailsDialog.show(this, record, detailsMember);
                return true;
            });

            if (record.memberId != null) {
                long memberId = record.memberId;
                row.setClickable(true);
                row.setFocusable(true);
                row.setBackgroundResource(R.drawable.focus_highlight);
                row.setOnClickListener(v -> {
                    Intent intent = new Intent(MainActivity.this, MemberDetailActivity.class);
                    intent.putExtra(MemberDetailActivity.EXTRA_MEMBER_ID, memberId);
                    startActivity(intent);
                });
            }

            recentActivityContainer.addView(row);
            if (i < recent.size() - 1) {
                recentActivityContainer.addView(UiUtil.createDivider(this, R.color.divider));
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
