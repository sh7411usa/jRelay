package com.sh7411usa.jrelay;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.sh7411usa.jrelay.sms.CommandProcessor;
import com.sh7411usa.jrelay.util.Prefs;

public class SendGroupMessageActivity extends BaseActivity {
    private EditText messageInput;
    private TextView stoppedBanner;
    private Button sendButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_send_group_message);
        messageInput = findViewById(R.id.edit_group_message);
        stoppedBanner = findViewById(R.id.banner_send_stopped);
        sendButton = findViewById(R.id.button_send);
        int[] plan = new CommandProcessor(this).planBroadcast();
        ((TextView) findViewById(R.id.text_send_cost)).setText(
                new Prefs(this).getDeliveryMode() == Prefs.DeliveryMode.GROUP_MMS
                        ? getString(R.string.send_group_cost_mms, plan[0], plan[1], plan[2])
                        : getString(R.string.send_group_cost_note, plan[2]));
        stoppedBanner.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        sendButton.setOnClickListener(v -> onSend());
        findViewById(R.id.button_cancel).setOnClickListener(v -> finish());
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean stopped = new Prefs(this).isStopped();
        stoppedBanner.setVisibility(stopped ? View.VISIBLE : View.GONE);
        sendButton.setEnabled(!stopped);
        // Disabled Send cannot take focus, so DPAD-down must skip to Cancel while stopped.
        messageInput.setNextFocusDownId(stopped ? R.id.button_cancel : R.id.button_send);
    }

    private void onSend() {
        if (new Prefs(this).isStopped()) return;
        String message = messageInput.getText().toString().trim();
        if (message.isEmpty()) return;
        new CommandProcessor(this).broadcastToGroup(message);
        finish();
    }
}
