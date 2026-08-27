package com.sh7411usa.jrelay;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.app.AlertDialog;

import com.sh7411usa.jrelay.db.MemberRepository;
import com.sh7411usa.jrelay.db.MessageRepository;
import com.sh7411usa.jrelay.db.OutboxRepository;
import com.sh7411usa.jrelay.model.Member;
import com.sh7411usa.jrelay.model.MessageRecord;
import com.sh7411usa.jrelay.util.MessageDetailsDialog;
import com.sh7411usa.jrelay.util.UiUtil;
import com.sh7411usa.jrelay.sms.SmsSendService;

import java.util.List;

public class FailedMessagesActivity extends Activity {
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTick = new Runnable() {
        @Override public void run() {
            if (!messageTouchActive) renderMessages();
            refreshHandler.postDelayed(this, 1000);
        }
    };
    private MessageRepository messages;
    private MemberRepository members;
    private LinearLayout container;
    private TextView emptyView;
    private boolean messageTouchActive;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_failed_messages);
        messages = new MessageRepository(this);
        members = new MemberRepository(this);
        container = findViewById(R.id.container_failed_messages);
        emptyView = findViewById(R.id.text_no_failed_messages);
        findViewById(R.id.button_retry_all_safe).setOnClickListener(v -> confirmRetryAll());
    }

    private void confirmRetryAll() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.retry_all_safe_title)
                .setMessage(R.string.retry_all_safe_warning)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.retry_all_safe, (dialog, which) -> {
                    int count = new OutboxRepository(this).retryAllSafe();
                    if (count > 0) SmsSendService.start(this);
                    Toast.makeText(this, getString(R.string.retry_all_safe_result, count), Toast.LENGTH_SHORT).show();
                    renderMessages();
                })
                .show();
    }

    @Override protected void onResume() {
        super.onResume();
        renderMessages();
        refreshHandler.postDelayed(refreshTick, 1000);
    }

    @Override protected void onPause() {
        super.onPause();
        refreshHandler.removeCallbacks(refreshTick);
    }

    private void renderMessages() {
        List<MessageRecord> records = messages.getFailedOutgoing(100);
        container.removeAllViews();
        emptyView.setVisibility(records.isEmpty() ? View.VISIBLE : View.GONE);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < records.size(); i++) {
            MessageRecord record = records.get(i);
            Member member = record.memberId == null ? null : members.findById(record.memberId);
            View row = inflater.inflate(R.layout.row_message, container, false);
            ((TextView) row.findViewById(R.id.text_message_body)).setText(record.body);
            String recipient = member == null ? record.deliveryPhone : member.nickname;
            String status = UiUtil.deliveryStatusLabel(this, record.deliveryStatus, record.deliveryErrorCode);
            ((TextView) row.findViewById(R.id.text_message_meta)).setText(
                    recipient + " • " + DateFormat.format("MMM d, h:mm a", record.timestamp) + " • " + status);
            row.setClickable(true);
            row.setFocusable(true);
            row.setLongClickable(true);
            row.setBackgroundResource(R.drawable.focus_highlight);
            row.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) messageTouchActive = true;
                if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    messageTouchActive = false;
                }
                return false;
            });
            row.setOnClickListener(v -> MessageDetailsDialog.show(this, record, member));
            row.setOnLongClickListener(v -> {
                MessageDetailsDialog.show(this, record, member);
                return true;
            });
            container.addView(row);
            if (i < records.size() - 1) container.addView(UiUtil.createDivider(this, R.color.divider));
        }
    }
}
