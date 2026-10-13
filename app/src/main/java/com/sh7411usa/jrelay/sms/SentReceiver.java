package com.sh7411usa.jrelay.sms;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.telephony.SmsManager;
import android.util.Log;

import com.sh7411usa.jrelay.R;
import com.sh7411usa.jrelay.db.MemberRepository;
import com.sh7411usa.jrelay.db.MessageRepository;
import com.sh7411usa.jrelay.db.OutboxRepository;
import com.sh7411usa.jrelay.model.Member;
import com.sh7411usa.jrelay.util.Prefs;

public class SentReceiver extends BroadcastReceiver {
    private static final String TAG = "SentReceiver";
    private static final String ACTION = "com.sh7411usa.jrelay.SMS_SENT";
    private static final String EXTRA_ROW_ID = "row_id";
    private static final String EXTRA_TOKEN = "token";
    // SmsManager threw synchronously; must never be -1 (RESULT_OK) or a small positive RESULT_ERROR_*.
    public static final int RESULT_NOT_SENT = -1000;
    static final long RETRY_DELAY_LIMIT_EXCEEDED_MILLIS = 5 * 60 * 1000L;
    static final long RETRY_DELAY_TRANSIENT_MILLIS = 60 * 1000L;
    static final long RETRY_DELAY_DEFAULT_MILLIS = 30 * 1000L;

    public static PendingIntent create(Context context, long rowId, int partIndex, long token) {
        Context app = context.getApplicationContext();
        Intent intent = new Intent(ACTION).setClass(app, SentReceiver.class);
        // Unique data Uri per part keeps PendingIntents distinct (extras don't count), even across process restarts.
        intent.setData(Uri.parse("jrelay://sent/" + rowId + "/" + partIndex + "/" + token));
        intent.putExtra(EXTRA_ROW_ID, rowId).putExtra(EXTRA_TOKEN, token);
        return PendingIntent.getBroadcast(app, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static int stringResFor(int resultCode) {
        switch (resultCode) {
            case Activity.RESULT_OK: return R.string.send_result_ok;
            case RESULT_NOT_SENT: return R.string.send_result_not_sent;
            case SmsManager.RESULT_ERROR_GENERIC_FAILURE: return R.string.send_result_generic;
            case SmsManager.RESULT_ERROR_RADIO_OFF: return R.string.send_result_radio_off;
            case SmsManager.RESULT_ERROR_NO_SERVICE: return R.string.send_result_no_service;
            case SmsManager.RESULT_ERROR_NULL_PDU: return R.string.send_result_null_pdu;
            case SmsManager.RESULT_ERROR_LIMIT_EXCEEDED: return R.string.send_result_limit_exceeded;
            case SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED: return R.string.send_result_short_code_not_allowed;
            default: return R.string.send_result_unknown;
        }
    }

    public static String describe(Context context, int resultCode) {
        int res = stringResFor(resultCode);
        return res == R.string.send_result_unknown ? context.getString(res, resultCode) : context.getString(res);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        long rowId = intent.getLongExtra(EXTRA_ROW_ID, -1);
        if (rowId < 0) return;
        long token = intent.getLongExtra(EXTRA_TOKEN, -1);
        Context app = context.getApplicationContext();
        OutboxRepository outbox = new OutboxRepository(app);
        OutboxRepository.OutboxItem item = outbox.findById(rowId);
        if (item == null) return;
        int resultCode = getResultCode();
        if (resultCode == Activity.RESULT_OK) {
            // recordPartSent marks SENT atomically when the last part lands; -1 = stale/duplicate.
            if (outbox.recordPartSent(item.id, token) != 0 || item.memberId == null) return;
            MemberRepository members = new MemberRepository(app);
            Member member = members.findById(item.memberId);
            if (member != null) members.resetFailedCount(member.id);
        } else if (outbox.recordSendFailure(item.id, token, resultCode)) {
            // false = another part already failed this row, or a stale token from a prior attempt.
            handleFailure(app, item, token, resultCode);
        }
    }

    static long retryDelayMillis(int resultCode) {
        switch (resultCode) {
            case SmsManager.RESULT_ERROR_LIMIT_EXCEEDED: return RETRY_DELAY_LIMIT_EXCEEDED_MILLIS;
            case SmsManager.RESULT_ERROR_RADIO_OFF:
            case SmsManager.RESULT_ERROR_NO_SERVICE: return RETRY_DELAY_TRANSIENT_MILLIS;
            default: return RETRY_DELAY_DEFAULT_MILLIS;
        }
    }

    public static void handleFailure(Context context, OutboxRepository.OutboxItem item, long token, int resultCode) {
        Context app = context.getApplicationContext();
        String reason = describe(app, resultCode);
        Log.w(TAG, "Send failed for outbox row " + item.id + ", resultCode=" + resultCode + " (" + reason + ")");
        OutboxRepository outbox = new OutboxRepository(app);
        Prefs prefs = new Prefs(app);
        int attempts = item.attempts + 1;
        if (attempts < Math.max(1, prefs.getRetryLimit() + 1)) {
            outbox.requeueForRetry(item.id, token, attempts, System.currentTimeMillis() + retryDelayMillis(resultCode));
            SmsSendService.start(app);
            return;
        }
        outbox.markFailed(item.id, token);
        new MessageRepository(app).log(item.memberId, "OUT", "FAILED",
                app.getString(R.string.tpl_failed_with_reason, item.body, reason));
        if (item.memberId != null) {
            MemberRepository members = new MemberRepository(app);
            Member member = members.findById(item.memberId);
            if (member == null) return;
            int failedCount = members.incrementFailedCount(member.id);
            int threshold = prefs.getFailureAlertThreshold();
            if (threshold > 0 && failedCount % threshold == 0) {
                new CommandProcessor(app).alertAdminsOfFailures(member, failedCount);
            }
        } else if (item.subgroupId != null) {
            // Group-MMS failure: alert once immediately, not per member.
            new CommandProcessor(app).alertAdminsOfGroupFailure(item.subgroupId);
        }
    }
}
