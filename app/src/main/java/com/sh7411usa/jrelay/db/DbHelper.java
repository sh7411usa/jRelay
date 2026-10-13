package com.sh7411usa.jrelay.db;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class DbHelper extends SQLiteOpenHelper {
    private static final String DB_NAME = "jrelay.db";
    private static final int DB_VERSION = 13;

    public static final String TABLE_MEMBERS = "members";
    public static final String TABLE_MESSAGE_LOG = "message_log";
    public static final String TABLE_OUTBOX = "outbox";
    public static final String TABLE_KNOWN_THREADS = "known_threads";
    public static final String TABLE_INGESTED_MMS = "ingested_mms";
    public static final String TABLE_JOIN_REQUESTS = "join_requests";
    public static final String TABLE_PROCESSED_SMS = "processed_sms";

    private static final String KNOWN_THREADS_COLS = "signature TEXT PRIMARY KEY,subgroup_id INTEGER NOT NULL,last_used_at INTEGER NOT NULL";
    private static final String INGESTED_MMS_COLS = "mms_id INTEGER PRIMARY KEY,ingested_at INTEGER NOT NULL,outcome TEXT";
    private static final String JOIN_REQUESTS_COLS = "phone_e164 TEXT PRIMARY KEY,nickname TEXT NOT NULL,requested_at INTEGER NOT NULL";

    private static DbHelper instance;

    public static synchronized DbHelper getInstance(Context context) {
        if (instance == null) instance = new DbHelper(context.getApplicationContext());
        return instance;
    }

    private DbHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_MEMBERS + " (id INTEGER PRIMARY KEY AUTOINCREMENT,phone_e164 TEXT UNIQUE NOT NULL,"
                + "nickname TEXT NOT NULL,is_admin INTEGER NOT NULL DEFAULT 0,is_muted INTEGER NOT NULL DEFAULT 0,"
                + "active INTEGER NOT NULL DEFAULT 1,added_by TEXT,created_at INTEGER NOT NULL,removed_at INTEGER,"
                + "rate_limit_custom INTEGER NOT NULL DEFAULT 0,rate_burst_min INTEGER,rate_burst_max INTEGER,"
                + "rate_min_wait INTEGER,rate_max_wait INTEGER,rate_initial_delay INTEGER,"
                + "daily_limit_custom INTEGER NOT NULL DEFAULT 0,daily_limit_value INTEGER,"
                + "daily_limit_bonus INTEGER NOT NULL DEFAULT 0,daily_limit_bonus_window_start INTEGER NOT NULL DEFAULT 0,"
                + "failed_count INTEGER NOT NULL DEFAULT 0,last_post_received_id INTEGER,subgroup_id INTEGER)");
        db.execSQL("CREATE TABLE " + TABLE_MESSAGE_LOG + " (id INTEGER PRIMARY KEY AUTOINCREMENT,member_id INTEGER,"
                + "direction TEXT NOT NULL,category TEXT NOT NULL,body TEXT NOT NULL,timestamp INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE " + TABLE_OUTBOX + " (id INTEGER PRIMARY KEY AUTOINCREMENT,member_id INTEGER,"
                + "phone_e164 TEXT NOT NULL,body TEXT NOT NULL,enqueued_at INTEGER NOT NULL,"
                + "status TEXT NOT NULL DEFAULT 'PENDING',attempts INTEGER NOT NULL DEFAULT 0,category TEXT,"
                + "hold_until INTEGER NOT NULL DEFAULT 0,apply_salt INTEGER NOT NULL DEFAULT 0,last_result INTEGER,"
                + "parts_pending INTEGER NOT NULL DEFAULT 0,handed_off_at INTEGER NOT NULL DEFAULT 0,subgroup_id INTEGER)");
        createTable(db, TABLE_KNOWN_THREADS, KNOWN_THREADS_COLS);
        createTable(db, TABLE_INGESTED_MMS, INGESTED_MMS_COLS);
        createTable(db, TABLE_JOIN_REQUESTS, JOIN_REQUESTS_COLS);
        createProcessedSms(db);
        createIndexes(db);
        createOutboxPhoneIndex(db);
        createOutboxHandoffIndex(db);
    }

    private static void createTable(SQLiteDatabase db, String table, String cols) {
        db.execSQL("CREATE TABLE " + table + " (" + cols + ")");
    }

    private static void addColumns(SQLiteDatabase db, String table, String... defs) {
        for (String def : defs) db.execSQL("ALTER TABLE " + table + " ADD COLUMN " + def);
    }

    private static void createIndex(SQLiteDatabase db, String name, String table, String cols) {
        db.execSQL("CREATE INDEX IF NOT EXISTS " + name + " ON " + table + "(" + cols + ")");
    }

    private static void createOutboxPhoneIndex(SQLiteDatabase db) {
        createIndex(db, "idx_outbox_phone_enqueued", TABLE_OUTBOX, "phone_e164, enqueued_at");
    }

    private static void createOutboxHandoffIndex(SQLiteDatabase db) {
        createIndex(db, "idx_outbox_phone_handoff", TABLE_OUTBOX, "phone_e164, handed_off_at");
    }

    private static void createProcessedSms(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_PROCESSED_SMS
                + " (dedupe_key TEXT PRIMARY KEY,processed_at INTEGER NOT NULL,source TEXT)");
    }

    private static void createIndexes(SQLiteDatabase db) {
        createIndex(db, "idx_message_log_timestamp", TABLE_MESSAGE_LOG, "timestamp");
        createIndex(db, "idx_message_log_member_timestamp", TABLE_MESSAGE_LOG, "member_id, timestamp");
        createIndex(db, "idx_outbox_status_hold", TABLE_OUTBOX, "status, hold_until");
    }

    // Migrations are additive so existing members, history, and queued sends survive an update.
    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) addColumns(db, TABLE_MEMBERS, "rate_limit_custom INTEGER NOT NULL DEFAULT 0",
                "rate_burst_min INTEGER", "rate_burst_max INTEGER", "rate_min_wait INTEGER",
                "rate_max_wait INTEGER", "rate_initial_delay INTEGER");
        if (oldVersion < 3) {
            addColumns(db, TABLE_MEMBERS, "daily_limit_custom INTEGER NOT NULL DEFAULT 0", "daily_limit_value INTEGER",
                    "daily_limit_bonus INTEGER NOT NULL DEFAULT 0",
                    "daily_limit_bonus_window_start INTEGER NOT NULL DEFAULT 0",
                    "failed_count INTEGER NOT NULL DEFAULT 0");
            addColumns(db, TABLE_OUTBOX, "attempts INTEGER NOT NULL DEFAULT 0");
        }
        if (oldVersion < 4) addColumns(db, TABLE_MEMBERS, "last_post_received_id INTEGER");
        if (oldVersion < 5) addColumns(db, TABLE_OUTBOX, "category TEXT",
                "hold_until INTEGER NOT NULL DEFAULT 0", "apply_salt INTEGER NOT NULL DEFAULT 0");
        if (oldVersion < 6) addColumns(db, TABLE_OUTBOX, "last_result INTEGER",
                "parts_pending INTEGER NOT NULL DEFAULT 0", "handed_off_at INTEGER NOT NULL DEFAULT 0");
        if (oldVersion < 7) createIndexes(db);
        // subgroup_id must stay nullable with no DEFAULT: existing rows get NULL (unassigned), never 0.
        if (oldVersion < 8) addColumns(db, TABLE_MEMBERS, "subgroup_id INTEGER");
        if (oldVersion < 9) addColumns(db, TABLE_OUTBOX, "subgroup_id INTEGER");
        if (oldVersion < 10) {
            createTable(db, TABLE_KNOWN_THREADS, KNOWN_THREADS_COLS);
            createTable(db, TABLE_INGESTED_MMS, INGESTED_MMS_COLS);
        }
        if (oldVersion < 11) createTable(db, TABLE_JOIN_REQUESTS, JOIN_REQUESTS_COLS);
        if (oldVersion < 12) {
            createProcessedSms(db);
            createOutboxPhoneIndex(db);
        }
        if (oldVersion < 13) createOutboxHandoffIndex(db);
    }

    public void wipeAllData() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_OUTBOX, null, null);
        db.delete(TABLE_MESSAGE_LOG, null, null);
        db.delete(TABLE_MEMBERS, null, null);
        db.delete(TABLE_JOIN_REQUESTS, null, null);
        // processed_sms deliberately NOT cleared: it stops the catch-up scan replaying handled texts.
    }
}
