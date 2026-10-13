package com.sh7411usa.jrelay;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.CheckBox;

import com.sh7411usa.jrelay.util.Prefs;

import java.util.ArrayList;
import java.util.List;

public class ConsentActivity extends BaseActivity {
    private static final int PERMISSION_REQUEST_CODE = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_consent);
        Button agreeButton = findViewById(R.id.button_agree);
        CheckBox licenseCheckbox = findViewById(R.id.checkbox_license_agree);
        agreeButton.setEnabled(licenseCheckbox.isChecked());
        licenseCheckbox.setOnCheckedChangeListener((b, isChecked) -> agreeButton.setEnabled(isChecked));
        agreeButton.setOnClickListener(v -> onAgree());
        findViewById(R.id.button_decline).setOnClickListener(v -> finish());
        findViewById(R.id.text_view_license_link).setOnClickListener(v ->
                startActivity(new Intent(this, LicenseActivity.class)));
    }

    private void onAgree() {
        List<String> toRequest = new ArrayList<>();
        for (String p : new String[]{Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_MMS,
                Build.VERSION.SDK_INT >= 33 ? Manifest.permission.POST_NOTIFICATIONS : null}) {
            if (p != null && checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) toRequest.add(p);
        }
        if (!toRequest.isEmpty()) {
            requestPermissions(toRequest.toArray(new String[0]), PERMISSION_REQUEST_CODE);
            return;
        }
        new Prefs(this).setConsentAccepted(true);
        goToMain();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PERMISSION_REQUEST_CODE) return;
        boolean criticalDenied = false, criticalPermanentlyDenied = false, notificationsDenied = false;
        for (int i = 0; i < permissions.length; i++) {
            if (i >= grantResults.length || grantResults[i] == PackageManager.PERMISSION_GRANTED) continue;
            String p = permissions[i];
            if (Manifest.permission.SEND_SMS.equals(p) || Manifest.permission.RECEIVE_SMS.equals(p)) {
                criticalDenied = true;
                if (!shouldShowRequestPermissionRationale(p)) criticalPermanentlyDenied = true;
            } else {
                notificationsDenied = true;
            }
        }
        if (criticalDenied) {
            AlertDialog.Builder b = new AlertDialog.Builder(this)
                    .setMessage(R.string.permission_denied_critical).setCancelable(false);
            if (criticalPermanentlyDenied) {
                b.setPositiveButton(R.string.action_open_app_settings, (d, w) -> startActivity(
                        new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(Uri.fromParts("package", getPackageName(), null))));
            } else {
                b.setPositiveButton(R.string.action_retry_permission, (d, w) -> onAgree());
            }
            b.setNegativeButton(R.string.action_cancel, null).show();
            return;
        }
        new Prefs(this).setConsentAccepted(true);
        if (!notificationsDenied) {
            goToMain();
            return;
        }
        new AlertDialog.Builder(this)
                .setMessage(R.string.permission_denied_notifications)
                .setCancelable(false)
                .setPositiveButton(R.string.action_continue, (d, w) -> goToMain())
                .show();
    }

    private void goToMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}
