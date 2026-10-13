package com.sh7411usa.jrelay.sms.mms;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.telephony.SmsManager;
import android.util.Log;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Sends one group MMS. Results go to the manifest-declared {@link MmsSentReceiver} (a dynamic
 * receiver would die with the process and strand the row); {@link Callback} only covers the
 * cases where no broadcast will ever arrive.
 */
public final class MmsSender {
    private static final String TAG = "MmsSender";
    static final String ACTION_MMS_SENT = "com.sh7411usa.jrelay.MMS_SENT";
    public static final int RESULT_NOT_SENT = -1000;
    public static final int RESULT_NO_RECIPIENTS = -2000;

    public interface Callback {
        void onResult(int resultCode);
    }

    private MmsSender() {
    }

    public static void send(Context context, long rowId, long token, List<String> recipientsE164,
                            String textBody, Callback callback) {
        Context appContext = context.getApplicationContext();
        if (recipientsE164 == null || recipientsE164.isEmpty()) {
            Log.e(TAG, "send() called with no recipients for row " + rowId
                    + " -- refusing to call SmsManager; this is a caller bug");
            callback.onResult(RESULT_NO_RECIPIENTS);
            return;
        }
        String transactionId = "jrelay-mms-" + rowId + "-" + token;
        byte[] pdu = MmsPduWriter.buildSendReq(recipientsE164, textBody, transactionId);
        // No PDU dump: it carries every recipient's number and the message text.
        Log.i(TAG, "Sending group MMS for row " + rowId + " to " + recipientsE164.size()
                + " recipients, pdu=" + pdu.length + " bytes");
        Uri contentUri;
        try {
            contentUri = MmsFileProvider.writeToCache(appContext, transactionId + ".pdu", pdu);
        } catch (IOException e) {
            Log.e(TAG, "Failed to write PDU to cache for row " + rowId + ", transactionId=" + transactionId, e);
            callback.onResult(RESULT_NOT_SENT);
            return;
        }
        // PendingIntent identity ignores extras: the data Uri must carry (rowId, token) uniqueness.
        Intent intent = new Intent(ACTION_MMS_SENT).setClass(appContext, MmsSentReceiver.class)
                .setData(Uri.parse(String.format(Locale.US, "jrelay://mms_sent/%d/%d", rowId, token)));
        PendingIntent sentIntent = PendingIntent.getBroadcast(appContext, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            SmsManager.getDefault().sendMultimediaMessage(appContext, contentUri, null, null, sentIntent);
        } catch (RuntimeException e) {
            // Synchronous throw: sentIntent will never fire, so report here.
            Log.e(TAG, "sendMultimediaMessage threw synchronously for row " + rowId
                    + ", transactionId=" + transactionId, e);
            callback.onResult(RESULT_NOT_SENT);
        }
    }

    /** Parses {@code jrelay://mms_sent/<rowId>/<token>} into {rowId, token}, or null. */
    static long[] parseSentData(Uri data) {
        if (data == null || !"jrelay".equals(data.getScheme()) || !"mms_sent".equals(data.getAuthority())) {
            return null;
        }
        List<String> segments = data.getPathSegments();
        if (segments.size() != 2) return null;
        try {
            return new long[]{Long.parseLong(segments.get(0)), Long.parseLong(segments.get(1))};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
