package com.sh7411usa.jrelay;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.text.InputType;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.sh7411usa.jrelay.db.MemberRepository;
import com.sh7411usa.jrelay.db.MessageRepository;
import com.sh7411usa.jrelay.model.Member;
import com.sh7411usa.jrelay.model.MessageRecord;
import com.sh7411usa.jrelay.sms.CommandProcessor;
import com.sh7411usa.jrelay.sms.PhoneNumberUtils;
import com.sh7411usa.jrelay.util.DailyLimitManager;
import com.sh7411usa.jrelay.util.Prefs;
import com.sh7411usa.jrelay.util.UiUtil;

import static com.sh7411usa.jrelay.util.UiUtil.show;

import java.util.List;
import java.util.concurrent.TimeUnit;

public class MemberDetailActivity extends BaseActivity {

    public static final String EXTRA_MEMBER_ID = "extra_member_id";

    private MemberRepository memberRepository;
    private MessageRepository messageRepository;
    private CommandProcessor commandProcessor;
    private Prefs prefs;
    private long memberId;
    private Member member;

    private TextView nicknameView, numberView, memberMetaView, adminBadgeView, mutedBadgeView, subgroupBadgeView;
    private TextView statSentView, statReceivedView, statTodayView, statActivityLevelView;
    private TextView activityEmptyView, dmStoppedView, dailyLimitUsageView;
    private LinearLayout activityContainer, dailyLimitFieldsContainer, dailyLimitValueContainer;
    private Button adminButton, muteButton, overrideDailyLimitButton, saveDailyLimitButton;
    private CheckBox customDailyLimitCheckbox, dailyLimitUnlimitedCheckbox;
    private EditText dailyLimitValueInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_member_detail);
        memberRepository = new MemberRepository(this);
        messageRepository = new MessageRepository(this);
        commandProcessor = new CommandProcessor(this);
        prefs = new Prefs(this);
        memberId = getIntent().getLongExtra(EXTRA_MEMBER_ID, -1);

        nicknameView = findViewById(R.id.text_nickname);
        numberView = findViewById(R.id.text_number);
        memberMetaView = findViewById(R.id.text_member_meta);
        adminBadgeView = findViewById(R.id.text_badge_admin);
        mutedBadgeView = findViewById(R.id.text_badge_muted);
        subgroupBadgeView = findViewById(R.id.text_badge_subgroup);
        statSentView = findViewById(R.id.text_stat_sent);
        statReceivedView = findViewById(R.id.text_stat_received);
        statTodayView = findViewById(R.id.text_stat_today);
        statActivityLevelView = findViewById(R.id.text_stat_activity_level);
        activityContainer = findViewById(R.id.container_member_activity);
        activityEmptyView = findViewById(R.id.text_member_activity_empty);
        adminButton = findViewById(R.id.button_toggle_admin);
        muteButton = findViewById(R.id.button_toggle_mute);
        dmStoppedView = findViewById(R.id.text_dm_stopped);
        customDailyLimitCheckbox = findViewById(R.id.checkbox_custom_daily_limit);
        dailyLimitFieldsContainer = findViewById(R.id.container_daily_limit_fields);
        dailyLimitUnlimitedCheckbox = findViewById(R.id.checkbox_daily_limit_unlimited);
        dailyLimitValueContainer = findViewById(R.id.container_daily_limit_value);
        dailyLimitValueInput = findViewById(R.id.edit_daily_limit_value);
        dailyLimitUsageView = findViewById(R.id.text_daily_limit_usage);
        overrideDailyLimitButton = findViewById(R.id.button_override_daily_limit);
        saveDailyLimitButton = findViewById(R.id.button_save_daily_limit);

        adminButton.setOnClickListener(v -> {
            if (isLastAdmin()) return;
            memberRepository.setAdmin(member.id, !member.isAdmin);
            refresh();
        });
        muteButton.setOnClickListener(v -> {
            memberRepository.setMuted(member.id, !member.isMuted);
            refresh();
        });
        findViewById(R.id.button_remove).setOnClickListener(v -> confirmRemove());
        findViewById(R.id.button_send_dm).setOnClickListener(v -> {
            // Stays focusable for D-pad; while stopped it explains instead of sending.
            if (prefs.isStopped()) {
                new AlertDialog.Builder(this).setTitle(R.string.action_send_dm)
                        .setMessage(R.string.member_detail_dm_stopped)
                        .setPositiveButton(android.R.string.ok, null).show();
                return;
            }
            showDmDialog();
        });
        findViewById(R.id.button_edit_nickname).setOnClickListener(v -> showEditNicknameDialog());
        findViewById(R.id.button_edit_number).setOnClickListener(v -> showEditNumberDialog());
        findViewById(R.id.button_export_contact).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_INSERT);
            intent.setType(ContactsContract.Contacts.CONTENT_TYPE);
            intent.putExtra(ContactsContract.Intents.Insert.NAME, member.nickname);
            intent.putExtra(ContactsContract.Intents.Insert.PHONE, member.phoneE164);
            startActivity(intent);
        });
        findViewById(R.id.button_call).setOnClickListener(v ->
                startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + member.phoneE164))));
        findViewById(R.id.button_text).setOnClickListener(v ->
                startActivity(new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + member.phoneE164))));
        customDailyLimitCheckbox.setOnCheckedChangeListener((b, checked) -> show(dailyLimitFieldsContainer, checked));
        dailyLimitUnlimitedCheckbox.setOnCheckedChangeListener((b, checked) -> show(dailyLimitValueContainer, !checked));
        saveDailyLimitButton.setOnClickListener(v -> saveDailyLimit());
        overrideDailyLimitButton.setOnClickListener(v -> overrideDailyLimit());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private boolean isLastAdmin() {
        if (!member.isAdmin || memberRepository.countActiveAdmins() > 1) return false;
        Toast.makeText(this, R.string.error_last_admin, Toast.LENGTH_SHORT).show();
        return true;
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
        show(dmStoppedView, prefs.isStopped());
        show(adminBadgeView, member.isAdmin);
        show(mutedBadgeView, member.isMuted);
        if (member.subgroupId != null) subgroupBadgeView.setText(getString(R.string.subgroup_row_badge, member.subgroupId));
        show(subgroupBadgeView, member.subgroupId != null);

        long lastActivity = messageRepository.lastActivityForMember(member.id);
        memberMetaView.setText(lastActivity > 0
                ? getString(R.string.label_member_meta_with_activity, formatDate(member.createdAt), formatDateTime(lastActivity))
                : getString(R.string.label_member_meta, formatDate(member.createdAt)));
        statSentView.setText(String.valueOf(messageRepository.countForMember(member.id, "IN")));
        statReceivedView.setText(String.valueOf(messageRepository.countForMember(member.id, "OUT")));
        statTodayView.setText(String.valueOf(messageRepository.countForMemberSince(member.id,
                new DailyLimitManager(this).currentWindowStart())));
        setActivityLevel(messageRepository.countInboundForMemberSince(member.id,
                System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)));
        populateDailyLimitFields();
        renderActivity();
    }

    private String formatDate(long millis) {
        return DateUtils.formatDateTime(this, millis,
                DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR | DateUtils.FORMAT_ABBREV_MONTH);
    }

    private String formatDateTime(long millis) {
        return DateUtils.formatDateTime(this, millis,
                DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_ABBREV_MONTH);
    }

    private void setActivityLevel(int recentCount) {
        int labelRes, colorRes;
        if (recentCount >= 7) {
            labelRes = R.string.activity_level_high;
            colorRes = R.color.success;
        } else if (recentCount >= 3) {
            labelRes = R.string.activity_level_medium;
            colorRes = R.color.primary;
        } else if (recentCount >= 1) {
            labelRes = R.string.activity_level_low;
            colorRes = R.color.warning;
        } else {
            labelRes = R.string.activity_level_none;
            colorRes = R.color.text_secondary;
        }
        statActivityLevelView.setText(labelRes);
        statActivityLevelView.setTextColor(getColor(colorRes));
    }

    private void populateDailyLimitFields() {
        customDailyLimitCheckbox.setChecked(member.dailyLimitCustom);
        show(dailyLimitFieldsContainer, member.dailyLimitCustom);
        boolean unlimited = member.dailyLimitCustom && member.dailyLimitValue == null;
        dailyLimitUnlimitedCheckbox.setChecked(unlimited);
        show(dailyLimitValueContainer, !unlimited);
        dailyLimitValueInput.setText(String.valueOf(
                member.dailyLimitValue != null ? member.dailyLimitValue : prefs.getDefaultIndividualLimitSeed()));
        if (!member.dailyLimitCustom) {
            dailyLimitUsageView.setText(R.string.daily_limit_not_customized);
            show(overrideDailyLimitButton, false);
            return;
        }
        DailyLimitManager.Status status = new DailyLimitManager(this).memberStatus(member);
        dailyLimitUsageView.setText(status.unlimited
                ? getString(R.string.tpl_daily_limit_usage_unlimited, status.used)
                : getString(R.string.tpl_daily_limit_usage, status.used, status.limit));
        show(overrideDailyLimitButton, status.isExhausted());
    }

    private void saveDailyLimit() {
        if (!customDailyLimitCheckbox.isChecked()) {
            memberRepository.clearDailyLimitOverride(member.id);
        } else if (dailyLimitUnlimitedCheckbox.isChecked()) {
            memberRepository.setDailyLimitOverride(member.id, null);
        } else {
            int value;
            try {
                value = Integer.parseInt(dailyLimitValueInput.getText().toString().trim());
            } catch (NumberFormatException e) {
                value = prefs.getDefaultIndividualLimitSeed();
            }
            memberRepository.setDailyLimitOverride(member.id, Math.max(0, value));
        }
        refresh();
        Toast.makeText(this, R.string.daily_limit_saved, Toast.LENGTH_SHORT).show();
    }

    private void overrideDailyLimit() {
        // Refresh hides this button; on DPAD move focus to Save instead of jumping to the top.
        boolean hadFocus = overrideDailyLimitButton.hasFocus() && !overrideDailyLimitButton.isInTouchMode();
        new DailyLimitManager(this).overrideMember(member);
        refresh();
        if (hadFocus && overrideDailyLimitButton.getVisibility() != View.VISIBLE) saveDailyLimitButton.requestFocus();
        Toast.makeText(this, R.string.daily_limit_override_done, Toast.LENGTH_SHORT).show();
    }

    private void renderActivity() {
        activityContainer.removeAllViews();
        List<MessageRecord> recent = messageRepository.getRecentForMember(member.id, 20);
        show(activityEmptyView, recent.isEmpty());
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < recent.size(); i++) {
            MessageRecord record = recent.get(i);
            View row = inflater.inflate(R.layout.row_message, activityContainer, false);
            ((TextView) row.findViewById(R.id.text_message_body)).setText(record.body);
            ((TextView) row.findViewById(R.id.text_message_meta)).setText(formatDateTime(record.timestamp));
            activityContainer.addView(row);
            if (i < recent.size() - 1) activityContainer.addView(UiUtil.createDivider(this, R.color.divider));
        }
    }

    private void confirmRemove() {
        if (isLastAdmin()) return;
        new AlertDialog.Builder(this).setTitle(R.string.confirm_remove_title)
                .setMessage(getString(R.string.confirm_remove_message, member.nickname))
                .setPositiveButton(R.string.action_remove, (d, w) -> {
                    commandProcessor.removeMember(member, getString(R.string.default_added_by_admin));
                    finish();
                })
                .setNegativeButton(R.string.action_cancel, null).show();
    }

    private interface InputHandler {
        void accept(EditText input);
    }

    private void showInputDialog(int titleRes, int positiveRes, int inputType, String initial, InputHandler onOk) {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_text_input, null);
        EditText input = dialogView.findViewById(R.id.edit_text_input);
        input.setInputType(inputType);
        if (inputType == InputType.TYPE_CLASS_PHONE) {
            // Keep "+1555..." from rendering as "1555...+" in RTL.
            input.setTextDirection(View.TEXT_DIRECTION_LTR);
            input.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        }
        if (initial != null) input.setText(initial);
        new AlertDialog.Builder(this).setTitle(titleRes).setView(dialogView)
                .setPositiveButton(positiveRes, (d, w) -> onOk.accept(input))
                .setNegativeButton(R.string.action_cancel, null).show();
    }

    private void showDmDialog() {
        showInputDialog(R.string.action_send_dm, R.string.action_send,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
                null, input -> {
                    String message = input.getText().toString().trim();
                    if (!message.isEmpty()) commandProcessor.sendDirectMessage(member, message);
                });
    }

    private void showEditNicknameDialog() {
        showInputDialog(R.string.action_edit_nickname, R.string.action_save,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME | InputType.TYPE_TEXT_FLAG_CAP_WORDS,
                member.nickname, input -> {
                    String nickname = input.getText().toString().trim();
                    if (nickname.isEmpty() || nickname.equals(member.nickname)) return;
                    commandProcessor.renameMemberFromApp(member, nickname, getString(R.string.default_added_by_admin));
                    refresh();
                });
    }

    private void showEditNumberDialog() {
        showInputDialog(R.string.action_edit_number, R.string.action_save, InputType.TYPE_CLASS_PHONE,
                member.phoneE164, input -> {
                    String normalized = PhoneNumberUtils.normalize(input.getText().toString());
                    if (normalized == null) return;
                    memberRepository.setPhone(member.id, normalized);
                    refresh();
                });
    }
}
