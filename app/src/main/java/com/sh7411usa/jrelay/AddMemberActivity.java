package com.sh7411usa.jrelay;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import com.sh7411usa.jrelay.db.MemberRepository;
import com.sh7411usa.jrelay.model.Member;
import com.sh7411usa.jrelay.sms.CommandProcessor;
import com.sh7411usa.jrelay.sms.PhoneNumberUtils;

public class AddMemberActivity extends Activity {

    private EditText numberInput;
    private EditText nicknameInput;
    private TextView errorView;
    private MemberRepository memberRepository;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_member);
        memberRepository = new MemberRepository(this);

        numberInput = findViewById(R.id.edit_phone_number);
        nicknameInput = findViewById(R.id.edit_nickname);
        errorView = findViewById(R.id.text_error);

        findViewById(R.id.button_save).setOnClickListener(v -> onSave());
        findViewById(R.id.button_cancel).setOnClickListener(v -> finish());
    }

    private void onSave() {
        String normalized = PhoneNumberUtils.normalize(numberInput.getText().toString());
        String nickname = nicknameInput.getText().toString().trim();

        if (normalized == null) {
            showError(R.string.error_invalid_number);
            return;
        }
        Member existing = memberRepository.findByPhone(normalized);
        if (existing != null && existing.active) {
            showError(R.string.error_duplicate_number);
            return;
        }

        new CommandProcessor(this).addMember(normalized, nickname, getString(R.string.default_added_by_admin));
        finish();
    }

    private void showError(int stringRes) {
        errorView.setText(stringRes);
        errorView.setVisibility(View.VISIBLE);
    }
}
