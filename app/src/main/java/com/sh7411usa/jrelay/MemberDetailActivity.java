package com.sh7411usa.jrelay;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.sh7411usa.jrelay.db.MemberRepository;
import com.sh7411usa.jrelay.db.MessageRepository;
import com.sh7411usa.jrelay.model.Member;
import com.sh7411usa.jrelay.model.MessageRecord;
import com.sh7411usa.jrelay.sms.CommandProcessor;
import com.sh7411usa.jrelay.sms.PhoneNumberUtils;
import com.sh7411usa.jrelay.util.UiUtil;
import com.sh7411usa.jrelay.util.MessageDetailsDialog;

import java.util.List;
import java.util.concurrent.TimeUnit;

public class MemberDetailActivity extends Activity {

    private static final long STATUS_REFRESH_MS = 1000;

    public static final String EXTRA_MEMBER_ID = "extra_member_id";

    private MemberRepository memberRepository;
    private MessageRepository messageRepository;
    private CommandProcessor commandProcessor;
    private long memberId;
    private Member member;

    private TextView nicknameView;
    private TextView numberView;
    private TextView statsView;
    private LinearLayout activityContainer;
    private Button adminButton;
    private Button muteButton;
    private boolean messageTouchActive;
    private final Handler statusHandler = new Handler(Looper.getMainLooper());
    private final Runnable statusRefresh = new Runnable() {
        @Override
        public void run() {
            if (member != null && !messageTouchActive) {
                renderActivity();
            }
            statusHandler.postDelayed(this, STATUS_REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_member_detail);

        memberRepository = new MemberRepository(this);
        messageRepository = new MessageRepository(this);
        commandProcessor = new CommandProcessor(this);
        memberId = getIntent().getLongExtra(EXTRA_MEMBER_ID, -1);

        nicknameView = findViewById(R.id.text_nickname);
        numberView = findViewById(R.id.text_number);
        statsView = findViewById(R.id.text_stats);
        activityContainer = findViewById(R.id.container_member_activity);
        adminButton = findViewById(R.id.button_toggle_admin);
        muteButton = findViewById(R.id.button_toggle_mute);

        adminButton.setOnClickListener(v -> {
            memberRepository.setAdmin(member.id, !member.isAdmin);
            refresh();
        });
        muteButton.setOnClickListener(v -> {
            memberRepository.setMuted(member.id, !member.isMuted);
            refresh();
        });
        findViewById(R.id.button_remove).setOnClickListener(v -> confirmRemove());
        findViewById(R.id.button_send_dm).setOnClickListener(v -> showDmDialog());
        findViewById(R.id.button_edit_nickname).setOnClickListener(v -> showEditNicknameDialog());
        findViewById(R.id.button_edit_number).setOnClickListener(v -> showEditNumberDialog());
        findViewById(R.id.button_export_contact).setOnClickListener(v -> exportContact());
        findViewById(R.id.button_call).setOnClickListener(v -> callMember());
        findViewById(R.id.button_text).setOnClickListener(v -> textMember());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        statusHandler.post(statusRefresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        statusHandler.removeCallbacks(statusRefresh);
    }

    private void refresh() {
        member = memberRepository.findById(memberId);
        if (member == null || !member.active) {
            finish();
            return;
        }
        nicknameView.setText(member.nickname);
        numberView.setText(member.phoneE164);
        adminButton.setText(member.isAdmin ? R.string.action_revoke_admin : R.string.action_make_admin);
        muteButton.setText(member.isMuted ? R.string.action_unmute : R.string.action_mute);

        int sentCount = messageRepository.countForMember(member.id, "IN");
        int receivedCount = messageRepository.countForMember(member.id, "OUT");
        long lastActivity = messageRepository.lastActivityForMember(member.id);
        long sevenDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7);
        int recentCount = messageRepository.countForMemberSince(member.id, sevenDaysAgo);

        StringBuilder stats = new StringBuilder();
        stats.append(getString(R.string.label_member_since, DateFormat.format("MMM d, yyyy", member.createdAt))).append("\n");
        if (lastActivity > 0) {
            stats.append(getString(R.string.label_last_activity, DateFormat.format("MMM d, yyyy h:mm a", lastActivity))).append("\n");
        }
        stats.append(getString(R.string.label_messages_sent, sentCount)).append("\n");
        stats.append(getString(R.string.label_messages_received, receivedCount)).append("\n");
        stats.append(getString(R.string.label_activity_level, activityLevelLabel(recentCount)));
        statsView.setText(stats.toString());

        renderActivity();
    }

    private String activityLevelLabel(int recentCount) {
        if (recentCount >= 20) {
            return getString(R.string.activity_level_high);
        } else if (recentCount >= 5) {
            return getString(R.string.activity_level_medium);
        } else if (recentCount >= 1) {
            return getString(R.string.activity_level_low);
        }
        return getString(R.string.activity_level_none);
    }

    private void renderActivity() {
        activityContainer.removeAllViews();
        List<MessageRecord> recent = messageRepository.getRecentForMember(member.id, 20);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (MessageRecord record : recent) {
            View row = inflater.inflate(R.layout.row_message, activityContainer, false);
            TextView bodyView = row.findViewById(R.id.text_message_body);
            TextView metaView = row.findViewById(R.id.text_message_meta);
            bodyView.setText(record.body);
            String meta = DateFormat.format("MMM d, h:mm a", record.timestamp).toString();
            String deliveryStatus = UiUtil.deliveryStatusLabel(this,
                    record.deliveryStatus, record.deliveryErrorCode);
            if (deliveryStatus != null) {
                meta += " • " + deliveryStatus;
            }
            metaView.setText(meta);
            row.setClickable(true);
            row.setFocusable(true);
            row.setLongClickable(true);
            row.setBackgroundResource(R.drawable.focus_highlight);
            row.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    messageTouchActive = true;
                } else if (event.getActionMasked() == MotionEvent.ACTION_UP ||
                        event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    messageTouchActive = false;
                }
                return false;
            });
            row.setOnLongClickListener(v -> {
                MessageDetailsDialog.show(this, record, member);
                return true;
            });
            activityContainer.addView(row);
        }
    }

    private void confirmRemove() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.confirm_remove_title)
                .setMessage(getString(R.string.confirm_remove_message, member.nickname))
                .setPositiveButton(R.string.action_remove, (dialog, which) -> {
                    commandProcessor.removeMember(member, getString(R.string.default_added_by_admin));
                    finish();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void showDmDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_text_input, null);
        EditText input = dialogView.findViewById(R.id.edit_text_input);
        new AlertDialog.Builder(this)
                .setTitle(R.string.action_send_dm)
                .setView(dialogView)
                .setPositiveButton(R.string.action_save, (dialog, which) -> {
                    String message = input.getText().toString().trim();
                    if (!message.isEmpty()) {
                        commandProcessor.sendDirectMessage(member, message);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void showEditNicknameDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_text_input, null);
        EditText input = dialogView.findViewById(R.id.edit_text_input);
        input.setText(member.nickname);
        new AlertDialog.Builder(this)
                .setTitle(R.string.action_edit_nickname)
                .setView(dialogView)
                .setPositiveButton(R.string.action_save, (dialog, which) -> {
                    String nickname = input.getText().toString().trim();
                    if (!nickname.isEmpty() && !nickname.equals(member.nickname)) {
                        commandProcessor.renameMemberFromApp(member, nickname, getString(R.string.default_added_by_admin));
                        refresh();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void showEditNumberDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_text_input, null);
        EditText input = dialogView.findViewById(R.id.edit_text_input);
        input.setText(member.phoneE164);
        new AlertDialog.Builder(this)
                .setTitle(R.string.action_edit_number)
                .setView(dialogView)
                .setPositiveButton(R.string.action_save, (dialog, which) -> {
                    String normalized = PhoneNumberUtils.normalize(input.getText().toString());
                    if (normalized != null) {
                        memberRepository.setPhone(member.id, normalized);
                        refresh();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void exportContact() {
        Intent intent = new Intent(Intent.ACTION_INSERT);
        intent.setType(ContactsContract.Contacts.CONTENT_TYPE);
        intent.putExtra(ContactsContract.Intents.Insert.NAME, member.nickname);
        intent.putExtra(ContactsContract.Intents.Insert.PHONE, member.phoneE164);
        startActivity(intent);
    }

    private void callMember() {
        Intent intent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + member.phoneE164));
        startActivity(intent);
    }

    private void textMember() {
        Intent intent = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + member.phoneE164));
        startActivity(intent);
    }
}
