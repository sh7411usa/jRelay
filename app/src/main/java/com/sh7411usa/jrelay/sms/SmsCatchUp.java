package com.sh7411usa.jrelay.sms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.database.Cursor;
import android.provider.Telephony;
import android.util.Log;

import com.sh7411usa.jrelay.db.ProcessedSmsRepository;
import com.sh7411usa.jrelay.util.Prefs;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Relays inbound SMS the live SmsReceiver missed by reading content://sms/inbox; dedupe errs toward missing, never duplicating. */
public final class SmsCatchUp {
    private static final String TAG = "SmsCatchUp";
    public static final String SOURCE_LIVE = "LIVE";
    public static final String SOURCE_CATCHUP = "CATCHUP";
    static final long SETTLE_MILLIS = 90_000L;
    static final long MAX_LOOKBACK_MILLIS = 48L * 60 * 60 * 1000;
    private static final long RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000;
    private static final long PRUNE_INTERVAL_MILLIS = 24L * 60 * 60 * 1000;
    private static long lastPruneMillis; // guarded by LOCK
    static final long BODY_GUARD_BUCKET_MILLIS = 10L * 60 * 1000;
    private static final int TYPE_INBOX = 1;
    private static final Object LOCK = new Object();

    private SmsCatchUp() {
    }

    /** Synchronous; call off the main thread. */
    public static void run(Context context) {
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            try {
                scan(app);
            } catch (Exception e) {
                Log.e(TAG, "SMS catch-up scan failed", e);
            }
        }
    }

    public static void runAsync(Context context) {
        runAsync(context, null);
    }

    public static void runAsync(Context context, BroadcastReceiver.PendingResult pendingResult) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                run(app);
            } finally {
                if (pendingResult != null) pendingResult.finish();
            }
        }, "SmsCatchUp").start();
    }

    /** True: caller owns the message and must process it; false: already handled by either path. */
    public static boolean claim(Context context, String normalizedSender, long serviceCenterMillis,
                                String body, long receivedAtMillis, String source) {
        String other = SOURCE_LIVE.equals(source) ? SOURCE_CATCHUP : SOURCE_LIVE;
        String hash = bodyHash(body);
        long bucket = receivedAtMillis / BODY_GUARD_BUCKET_MILLIS;
        String[] refuse = {
                timeGuardKey(other, normalizedSender, serviceCenterMillis),
                bodyGuardKey(other, normalizedSender, hash, bucket - 1),
                bodyGuardKey(other, normalizedSender, hash, bucket),
                bodyGuardKey(other, normalizedSender, hash, bucket + 1),
        };
        String[] record = {
                timeGuardKey(source, normalizedSender, serviceCenterMillis),
                bodyGuardKey(source, normalizedSender, hash, bucket),
        };
        return new ProcessedSmsRepository(context).claim(
                dedupeKey(normalizedSender, serviceCenterMillis, body), refuse, record, source);
    }

    private static void scan(Context context) {
        Prefs prefs = new Prefs(context);
        long now = System.currentTimeMillis();
        long since = prefs.getSmsCatchUpSinceMillis();
        if (since == 0) { // first run: seed the watermark, never relay older history
            prefs.setSmsCatchUpSinceMillis(now);
            return;
        }
        long lower = Math.max(since, now - MAX_LOOKBACK_MILLIS);
        long upper = now - SETTLE_MILLIS;
        if (upper <= lower) return;
        if (now - lastPruneMillis >= PRUNE_INTERVAL_MILLIS) {
            new ProcessedSmsRepository(context).pruneOlderThan(now - RETENTION_MILLIS);
            lastPruneMillis = now;
        }
        Cursor cursor;
        try {
            cursor = context.getContentResolver().query(
                    Telephony.Sms.Inbox.CONTENT_URI,
                    new String[]{Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.DATE_SENT},
                    Telephony.Sms.TYPE + " = ? AND " + Telephony.Sms.DATE + " > ? AND " + Telephony.Sms.DATE + " <= ?",
                    new String[]{String.valueOf(TYPE_INBOX), String.valueOf(lower), String.valueOf(upper)},
                    Telephony.Sms.DATE + " ASC");
        } catch (SecurityException e) {
            Log.e(TAG, "READ_SMS denied; cannot catch up on missed SMS", e);
            return;
        }
        if (cursor == null) {
            Log.w(TAG, "content://sms/inbox query returned null cursor");
            return;
        }
        CommandProcessor processor = new CommandProcessor(context);
        long newestExamined = since;
        boolean processedAny = false;
        try {
            int addressCol = cursor.getColumnIndex(Telephony.Sms.ADDRESS);
            int bodyCol = cursor.getColumnIndex(Telephony.Sms.BODY);
            int dateCol = cursor.getColumnIndex(Telephony.Sms.DATE);
            int dateSentCol = cursor.getColumnIndex(Telephony.Sms.DATE_SENT);
            if (addressCol < 0 || bodyCol < 0 || dateCol < 0 || dateSentCol < 0) {
                Log.e(TAG, "content://sms/inbox cursor missing expected columns");
                return;
            }
            while (cursor.moveToNext()) {
                long date = cursor.getLong(dateCol);
                long dateSent = cursor.getLong(dateSentCol);
                String sender = PhoneNumberUtils.normalize(cursor.getString(addressCol));
                String body = cursor.getString(bodyCol);
                if (sender != null && body != null && !body.trim().isEmpty()) {
                    if (dateSent <= 0) { // no key to match the live path; skipping can only miss, not duplicate
                        Log.w(TAG, "Skipping inbox SMS with no date_sent, received " + date);
                    } else if (claim(context, sender, dateSent, body, date, SOURCE_CATCHUP)) {
                        processor.handleIncoming(sender, body, date); // receive time, not now: echo check needs it
                        processedAny = true;
                        Log.i(TAG, "Caught up missed SMS from " + PhoneNumberUtils.mask(sender) + " received " + date);
                    }
                }
                // Advance only after the row is fully handled; saved in finally.
                newestExamined = Math.max(newestExamined, date);
            }
        } finally {
            cursor.close();
            if (newestExamined > since) prefs.setSmsCatchUpSinceMillis(newestExamined);
            if (processedAny) SmsSendService.start(context);
        }
    }

    public static String concatBodies(String[] partBodies) {
        StringBuilder sb = new StringBuilder();
        for (String part : partBodies) if (part != null) sb.append(part);
        return sb.toString();
    }

    public static String dedupeKey(String normalizedSender, long serviceCenterMillis, String body) {
        return normalizedSender + "|" + serviceCenterMillis + "|" + bodyHash(body);
    }

    static String timeGuardKey(String source, String normalizedSender, long serviceCenterMillis) {
        return "T|" + source + "|" + normalizedSender + "|" + serviceCenterMillis;
    }

    static String bodyGuardKey(String source, String normalizedSender, String bodyHash, long bucket) {
        return "B|" + source + "|" + normalizedSender + "|" + bodyHash + "|" + bucket;
    }

    /** SHA-256 hex of the body minus all whitespace/control chars; null hashes like "". */
    static String bodyHash(String body) {
        StringBuilder canonical = new StringBuilder();
        if (body != null) {
            for (int i = 0; i < body.length(); i++) {
                char c = body.charAt(i);
                if (!Character.isWhitespace(c) && !Character.isSpaceChar(c) && !Character.isISOControl(c)) canonical.append(c);
            }
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
