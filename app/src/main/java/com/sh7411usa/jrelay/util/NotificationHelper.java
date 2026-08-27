package com.sh7411usa.jrelay.util;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.sh7411usa.jrelay.MainActivity;
import com.sh7411usa.jrelay.R;

public class NotificationHelper {

    private static final String CHANNEL_ID = "admin_messages";
    private static final String SMS_STATUS_CHANNEL_ID = "sms_delivery_status";
    private static final int NOTIFICATION_ID_BASE = 1000;
    private static int notificationCounter = 0;

    public static void showAdminMessage(Context context, String text) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = nm.getNotificationChannel(CHANNEL_ID);
            if (channel == null) {
                channel = new NotificationChannel(CHANNEL_ID,
                        context.getString(R.string.notification_channel_admin),
                        NotificationManager.IMPORTANCE_HIGH);
                nm.createNotificationChannel(channel);
            }
            builder = new Notification.Builder(context, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(context);
            builder.setPriority(Notification.PRIORITY_HIGH);
        }

        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent, flags);

        builder.setContentTitle(context.getString(R.string.notification_admin_title))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true);

        nm.notify(NOTIFICATION_ID_BASE + (++notificationCounter), builder.build());
    }

    public static void showSmsFailure(Context context, int errorCode) {
        showSmsStatus(context, context.getString(R.string.notification_sms_failed_title),
                context.getString(R.string.notification_sms_failed_body, errorCode));
    }

    public static void showSmsDeliveryFailure(Context context, int errorCode) {
        showSmsStatus(context, context.getString(R.string.notification_delivery_failed_title),
                context.getString(R.string.notification_delivery_failed_body, errorCode));
    }

    private static void showSmsStatus(Context context, String title, String text) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = nm.getNotificationChannel(SMS_STATUS_CHANNEL_ID);
            if (channel == null) {
                channel = new NotificationChannel(SMS_STATUS_CHANNEL_ID,
                        context.getString(R.string.notification_channel_sms_status),
                        NotificationManager.IMPORTANCE_HIGH);
                nm.createNotificationChannel(channel);
            }
            builder = new Notification.Builder(context, SMS_STATUS_CHANNEL_ID);
        } else {
            builder = new Notification.Builder(context);
            builder.setPriority(Notification.PRIORITY_HIGH);
        }
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(context, 1, intent, flags);
        builder.setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true);
        nm.notify(NOTIFICATION_ID_BASE + (++notificationCounter), builder.build());
    }
}
