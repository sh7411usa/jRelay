package com.sh7411usa.jrelay.util;

import android.app.AlertDialog;
import android.content.Context;
import android.telephony.SmsManager;
import android.text.format.DateFormat;
import android.widget.Toast;

import com.sh7411usa.jrelay.R;
import com.sh7411usa.jrelay.model.Member;
import com.sh7411usa.jrelay.model.MessageRecord;
import com.sh7411usa.jrelay.db.OutboxRepository;
import com.sh7411usa.jrelay.sms.SmsSendService;

public final class MessageDetailsDialog {

    private MessageDetailsDialog() {
    }

    public static void show(Context context, MessageRecord record, Member member) {
        boolean outgoing = "OUT".equals(record.direction);
        StringBuilder details = new StringBuilder();
        append(details, context.getString(R.string.details_direction),
                context.getString(outgoing ? R.string.details_outgoing : R.string.details_incoming));
        append(details, context.getString(R.string.details_type), categoryLabel(context, record.category));
        append(details, context.getString(outgoing ? R.string.details_recipient : R.string.details_sender),
                member == null ? context.getString(R.string.details_unknown_member) : member.nickname);

        String phone = record.deliveryPhone != null
                ? record.deliveryPhone
                : member == null ? null : member.phoneE164;
        if (phone != null) {
            append(details, context.getString(R.string.details_number), phone);
        }
        append(details, context.getString(outgoing ? R.string.details_created : R.string.details_received),
                formatTime(record.timestamp));
        append(details, context.getString(R.string.details_characters), String.valueOf(record.body.length()));

        if (outgoing) {
            String status = UiUtil.deliveryStatusLabel(context,
                    record.deliveryStatus, record.deliveryErrorCode);
            append(details, context.getString(R.string.details_status), status == null
                    ? context.getString(R.string.details_unavailable) : status);
            appendIfPresent(details, context.getString(R.string.details_queued), record.enqueuedAt);
            appendIfPresent(details, context.getString(R.string.details_submitted), record.submittedAt);
            appendIfPresent(details, context.getString(R.string.details_delivered), record.deliveredAt);
            if (record.attemptCount != null) {
                append(details, context.getString(R.string.details_attempts), String.valueOf(record.attemptCount));
            }
            appendIfPresent(details, context.getString(R.string.details_last_attempt), record.lastAttemptAt);
            appendIfPresent(details, context.getString(R.string.details_next_retry), record.nextRetryAt);
            if (record.partsTotal != null) {
                append(details, context.getString(R.string.details_segments),
                        context.getString(R.string.details_segment_progress,
                                value(record.partsSent), value(record.partsDelivered), record.partsTotal));
            }
            if (record.deliveryErrorCode != null) {
                append(details, context.getString(R.string.details_error),
                        errorLabel(context, record.deliveryErrorCode));
            }
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setTitle(R.string.message_details_title)
                .setMessage(details.toString())
                .setPositiveButton(android.R.string.ok, null);
        if (canRetry(record)) {
            builder.setNeutralButton(R.string.action_retry, (dialog, which) -> confirmRetry(context, record.outboxId));
        }
        builder.show();
    }

    private static boolean canRetry(MessageRecord record) {
        return record.outboxId != null && value(record.partsSent) == 0 &&
                ("FAILED".equals(record.deliveryStatus) || "RETRY_PENDING".equals(record.deliveryStatus));
    }

    private static void confirmRetry(Context context, long outboxId) {
        new AlertDialog.Builder(context)
                .setTitle(R.string.retry_message_title)
                .setMessage(R.string.retry_message_warning)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.action_retry, (dialog, which) -> {
                    if (new OutboxRepository(context).retryNow(outboxId)) {
                        SmsSendService.start(context);
                        Toast.makeText(context, R.string.retry_queued, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(context, R.string.retry_not_available, Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }

    private static void append(StringBuilder sb, String label, String value) {
        if (sb.length() > 0) {
            sb.append('\n');
        }
        sb.append(label).append(": ").append(value);
    }

    private static void appendIfPresent(StringBuilder sb, String label, Long timestamp) {
        if (timestamp != null) {
            append(sb, label, formatTime(timestamp));
        }
    }

    private static String formatTime(long timestamp) {
        return DateFormat.format("MMM d, yyyy h:mm:ss a", timestamp).toString();
    }

    private static String categoryLabel(Context context, String category) {
        if ("COMMAND".equals(category)) return context.getString(R.string.details_type_command);
        if ("ADMIN".equals(category)) return context.getString(R.string.details_type_admin);
        if ("DM".equals(category)) return context.getString(R.string.details_type_direct);
        if ("SYSTEM".equals(category)) return context.getString(R.string.details_type_system);
        return context.getString(R.string.details_type_relay);
    }

    private static String errorLabel(Context context, int code) {
        int description;
        switch (code) {
            case SmsManager.RESULT_ERROR_GENERIC_FAILURE:
                description = R.string.sms_error_generic; break;
            case SmsManager.RESULT_ERROR_RADIO_OFF:
                description = R.string.sms_error_radio_off; break;
            case SmsManager.RESULT_ERROR_NULL_PDU:
                description = R.string.sms_error_null_pdu; break;
            case SmsManager.RESULT_ERROR_NO_SERVICE:
                description = R.string.sms_error_no_service; break;
            case SmsManager.RESULT_ERROR_LIMIT_EXCEEDED:
                description = R.string.sms_error_limit; break;
            case SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE:
                description = R.string.sms_error_fdn; break;
            default:
                return String.valueOf(code);
        }
        return context.getString(R.string.details_error_with_code, code, context.getString(description));
    }
}
