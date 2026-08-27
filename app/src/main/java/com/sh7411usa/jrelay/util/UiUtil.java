package com.sh7411usa.jrelay.util;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;

import com.sh7411usa.jrelay.R;

public class UiUtil {

    private UiUtil() {
    }

    /** A thin 1dp divider line for separating rows inflated into a vertical LinearLayout. */
    public static View createDivider(Context context, int colorResId) {
        View divider = new View(context);
        int heightPx = Math.round(1 * context.getResources().getDisplayMetrics().density);
        divider.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, heightPx));
        divider.setBackgroundColor(context.getColor(colorResId));
        return divider;
    }

    public static String deliveryStatusLabel(Context context, String status, Integer errorCode) {
        if (status == null) {
            return null;
        }
        int label;
        switch (status) {
            case "PENDING": label = R.string.status_pending; break;
            case "SENDING": label = R.string.status_sending; break;
            case "RETRY_PENDING": label = R.string.status_retry_pending; break;
            case "SENT": label = R.string.status_sent; break;
            case "DELIVERED": label = R.string.status_delivered; break;
            case "DELIVERY_FAILED": label = R.string.status_delivery_failed; break;
            default: label = R.string.status_failed; break;
        }
        String text = context.getString(label);
        return errorCode == null ? text : context.getString(R.string.delivery_status_error, text, errorCode);
    }
}
