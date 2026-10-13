package com.sh7411usa.jrelay.util;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;

public class UiUtil {
    private UiUtil() {}

    public static View createDivider(Context context, int colorResId) {
        View divider = new View(context);
        divider.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Math.round(context.getResources().getDisplayMetrics().density)));
        divider.setBackgroundColor(context.getColor(colorResId));
        return divider;
    }

    public static void show(View v, boolean visible) {
        v.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    public static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
