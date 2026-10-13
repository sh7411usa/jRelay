package com.sh7411usa.jrelay;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;

import com.sh7411usa.jrelay.util.LocaleThemeUtil;

public abstract class BaseActivity extends Activity {
    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleThemeUtil.wrap(newBase));
    }

    // API 35+ forces edge-to-edge: pad android.R.id.content by the system bars on every screen.
    @Override
    public void setContentView(int layoutResID) {
        super.setContentView(layoutResID);
        final View content = findViewById(android.R.id.content);
        if (content == null) return;
        // Capture base padding once so repeated inset dispatches do not accumulate.
        final int l = content.getPaddingLeft(), t = content.getPaddingTop(),
                r = content.getPaddingRight(), b = content.getPaddingBottom();
        content.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                v.setPadding(l + bars.left, t + bars.top, r + bars.right, b + bars.bottom);
            } else {
                v.setPadding(l + insets.getSystemWindowInsetLeft(), t + insets.getSystemWindowInsetTop(),
                        r + insets.getSystemWindowInsetRight(), b + insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        content.requestApplyInsets();
    }
}
