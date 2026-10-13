package com.sh7411usa.jrelay.util;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import com.sh7411usa.jrelay.MainActivity;
import com.sh7411usa.jrelay.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class NotificationHelper {
    private static final String TAG = "NotificationHelper";
    private static final String CHANNEL_ID = "admin_messages";
    private static final String SEND_CHANNEL_ID = "send_service";
    // One fixed id: distinct per-alert ids pile up on an unattended phone until Android's cap drops alerts.
    private static final int ADMIN_NOTIFICATION_ID = 1000;
    private static final int MAX_ALERT_LINES = 8;
    private static final List<String> recentAlerts = Collections.synchronizedList(new ArrayList<String>());
    public static final int FOREGROUND_NOTIFICATION_ID = 1;

    public static void showAdminMessage(Context context, String text) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (!nm.areNotificationsEnabled()) {
            Log.e(TAG, "Admin alert suppressed, notifications disabled ("
                    + (text == null ? 0 : text.length()) + " chars)");
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ensureChannel(context, nm, CHANNEL_ID, R.string.notification_channel_admin,
                    NotificationManager.IMPORTANCE_HIGH, 0);
        }
        Notification.InboxStyle inbox = new Notification.InboxStyle();
        int total;
        synchronized (recentAlerts) {
            recentAlerts.add(text);
            while (recentAlerts.size() > MAX_ALERT_LINES) recentAlerts.remove(0);
            for (String line : recentAlerts) inbox.addLine(line);
            total = recentAlerts.size();
        }
        inbox.setBigContentTitle(context.getString(R.string.notification_admin_title));
        inbox.setSummaryText(String.valueOf(total));
        nm.notify(ADMIN_NOTIFICATION_ID, builder(context, CHANNEL_ID, Notification.PRIORITY_HIGH,
                R.string.notification_admin_title, text).setStyle(inbox).setAutoCancel(true).build());
    }

    public static void ensureSendChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;
        ensureChannel(context, nm, SEND_CHANNEL_ID, R.string.notif_send_channel_name,
                NotificationManager.IMPORTANCE_LOW, R.string.notif_send_channel_desc);
    }

    public static Notification buildSendNotification(Context context, String statusLine) {
        ensureSendChannel(context);
        return builder(context, SEND_CHANNEL_ID, Notification.PRIORITY_LOW, R.string.notif_send_title, statusLine)
                .setOngoing(true).setAutoCancel(false).build();
    }

    /** No-op unless the ongoing notification is already showing. */
    public static void updateSendNotification(Context context, String statusLine) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;
        for (StatusBarNotification sbn : nm.getActiveNotifications()) {
            if (sbn.getId() == FOREGROUND_NOTIFICATION_ID) {
                nm.notify(FOREGROUND_NOTIFICATION_ID, buildSendNotification(context, statusLine));
                return;
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.O)
    private static void ensureChannel(Context context, NotificationManager nm, String id, int nameRes,
                                      int importance, int descRes) {
        if (nm.getNotificationChannel(id) != null) return;
        NotificationChannel channel = new NotificationChannel(id, context.getString(nameRes), importance);
        if (descRes != 0) channel.setDescription(context.getString(descRes));
        nm.createNotificationChannel(channel);
    }

    private static Notification.Builder builder(Context context, String channelId, int legacyPriority,
                                                int titleRes, String text) {
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, channelId)
                : new Notification.Builder(context).setPriority(legacyPriority);
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return b.setContentTitle(context.getString(titleRes)).setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentIntent(PendingIntent.getActivity(context, 0, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
    }
}
