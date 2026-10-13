package com.sh7411usa.jrelay.sms.mms;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Telephony;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Read-only access to content://mms. Never writes (only the default SMS app may). */
public final class MmsReader {
    private static final String TAG = "MmsReader";
    private static final Uri MMS_URI = Telephony.Mms.CONTENT_URI;
    private static final int MSG_BOX_INBOX = 1;
    // 132 = m-retrieve-conf (body readable); 130 = notification placeholder, must be skipped.
    private static final int M_TYPE_RETRIEVE_CONF = 132;
    private static final int ADDR_TYPE_FROM = 137;
    private static final int ADDR_TYPE_TO = 151;

    private final Context context;

    public MmsReader(Context context) {
        this.context = context.getApplicationContext();
    }

    public static final class InboundMms {
        public final long id;
        public final long timestampMs;
        public final String sender;
        public final List<String> recipients;
        public final String body;

        public InboundMms(long id, long timestampMs, String sender, List<String> recipients, String body) {
            this.id = id;
            this.timestampMs = timestampMs;
            this.sender = sender;
            this.recipients = recipients;
            this.body = body;
        }
    }

    public static final class ReadResult {
        public final List<InboundMms> messages;
        public final boolean permissionDenied;

        private ReadResult(List<InboundMms> messages, boolean permissionDenied) {
            this.messages = messages;
            this.permissionDenied = permissionDenied;
        }

        static ReadResult ok(List<InboundMms> messages) {
            return new ReadResult(messages, false);
        }

        static ReadResult denied() {
            return new ReadResult(Collections.<InboundMms>emptyList(), true);
        }
    }

    /** Telephony.Mms.DATE is in seconds. */
    public ReadResult fetchRecentInboxIds(long sinceEpochSeconds) {
        List<InboundMms> ids = new ArrayList<>();
        try (Cursor c = context.getContentResolver().query(MMS_URI,
                new String[]{Telephony.Mms._ID, Telephony.Mms.DATE},
                Telephony.Mms.MESSAGE_BOX + " = ? AND " + Telephony.Mms.MESSAGE_TYPE + " = ? AND " + Telephony.Mms.DATE + " > ?",
                new String[]{String.valueOf(MSG_BOX_INBOX), String.valueOf(M_TYPE_RETRIEVE_CONF), String.valueOf(sinceEpochSeconds)},
                Telephony.Mms.DATE + " ASC")) {
            if (c == null) {
                Log.w(TAG, "content://mms query returned null cursor");
                return ReadResult.ok(ids);
            }
            int idCol = c.getColumnIndex(Telephony.Mms._ID), dateCol = c.getColumnIndex(Telephony.Mms.DATE);
            if (idCol < 0 || dateCol < 0) {
                Log.e(TAG, "content://mms cursor missing expected columns (_id=" + idCol + ", date=" + dateCol + ")");
                return ReadResult.ok(ids);
            }
            while (c.moveToNext()) {
                ids.add(new InboundMms(c.getLong(idCol), c.getLong(dateCol) * 1000L, null, Collections.<String>emptyList(), null));
            }
            return ReadResult.ok(ids);
        } catch (SecurityException e) {
            Log.e(TAG, "READ_SMS denied while querying content://mms -- cannot read inbound MMS replies", e);
            return ReadResult.denied();
        }
    }

    /** Returns null (skip, not fatal) if sender or text part can't be found. */
    public InboundMms readMessage(long id, long timestampMs) {
        try {
            String sender = null;
            List<String> recipients = new ArrayList<>();
            try (Cursor c = context.getContentResolver().query(Uri.parse(MMS_URI + "/" + id + "/addr"),
                    new String[]{Telephony.Mms.Addr.ADDRESS, Telephony.Mms.Addr.TYPE}, null, null, null)) {
                if (c == null) {
                    Log.w(TAG, "content://mms/" + id + "/addr query returned null cursor");
                } else {
                    int addressCol = c.getColumnIndex(Telephony.Mms.Addr.ADDRESS), typeCol = c.getColumnIndex(Telephony.Mms.Addr.TYPE);
                    if (addressCol < 0 || typeCol < 0) {
                        Log.e(TAG, "content://mms/" + id + "/addr cursor missing expected columns");
                    } else {
                        while (c.moveToNext()) {
                            String address = c.getString(addressCol);
                            int type = c.getInt(typeCol);
                            if (address == null) continue;
                            if (type == ADDR_TYPE_FROM) sender = address;
                            else if (type == ADDR_TYPE_TO) recipients.add(address);
                        }
                    }
                }
            }
            if (sender == null) {
                Log.w(TAG, "no FROM address found for mms id=" + id + "; skipping");
                return null;
            }
            String body = readTextBody(id);
            if (body == null) {
                Log.w(TAG, "no text/plain part found (or readable) for mms id=" + id);
                return null;
            }
            return new InboundMms(id, timestampMs, sender, recipients, body);
        } catch (SecurityException e) {
            Log.e(TAG, "READ_SMS denied while reading mms id=" + id, e);
            return null;
        }
    }

    // Select by ct=text/plain: an application/smil part is always present too. Text may be null inline -> stream it.
    private String readTextBody(long id) {
        try (Cursor c = context.getContentResolver().query(Uri.withAppendedPath(MMS_URI, "part"),
                new String[]{Telephony.Mms.Part._ID, Telephony.Mms.Part.CONTENT_TYPE, Telephony.Mms.Part.TEXT},
                Telephony.Mms.Part.MSG_ID + " = ? AND " + Telephony.Mms.Part.CONTENT_TYPE + " = ?",
                new String[]{String.valueOf(id), "text/plain"}, null)) {
            if (c == null) {
                Log.w(TAG, "content://mms/part query returned null cursor for mms id=" + id);
                return null;
            }
            int partIdCol = c.getColumnIndex(Telephony.Mms.Part._ID), textCol = c.getColumnIndex(Telephony.Mms.Part.TEXT);
            if (partIdCol < 0 || textCol < 0) {
                Log.e(TAG, "content://mms/part cursor missing expected columns for mms id=" + id);
                return null;
            }
            if (!c.moveToFirst()) return null;
            String inline = c.getString(textCol);
            return inline != null ? inline : readTextPartFromStream(c.getLong(partIdCol));
        }
    }

    private String readTextPartFromStream(long partId) {
        InputStream in = null;
        try {
            in = context.getContentResolver().openInputStream(Uri.parse(MMS_URI + "/part/" + partId));
            if (in == null) {
                Log.w(TAG, "openInputStream returned null for mms part id=" + partId);
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n; (n = in.read(buffer)) != -1; ) out.write(buffer, 0, n);
            return out.toString("UTF-8");
        } catch (SecurityException e) {
            Log.e(TAG, "READ_SMS denied while streaming mms part id=" + partId, e);
            return null;
        } catch (IOException e) {
            Log.e(TAG, "failed reading mms part id=" + partId + " from stream", e);
            return null;
        } finally {
            // Close errors are swallowed on purpose; a try-with-resources would null out a good read.
            if (in != null) try { in.close(); } catch (IOException ignored) { }
        }
    }
}
