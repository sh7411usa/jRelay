package com.sh7411usa.jrelay;

import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.sh7411usa.jrelay.db.MemberRepository;
import com.sh7411usa.jrelay.model.Member;
import com.sh7411usa.jrelay.sms.CommandProcessor;
import com.sh7411usa.jrelay.sms.PhoneNumberUtils;
import com.sh7411usa.jrelay.util.Prefs;
import com.sh7411usa.jrelay.util.RelayIdentity;

public class AddMemberActivity extends BaseActivity {
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
        nicknameInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_DONE) return false;
            onSave();
            return true;
        });
        findViewById(R.id.button_save).setOnClickListener(v -> onSave());
        findViewById(R.id.button_cancel).setOnClickListener(v -> finish());
    }

    private void onSave() {
        String normalized = PhoneNumberUtils.normalize(numberInput.getText().toString());
        String nickname = nicknameInput.getText().toString().trim();
        Member existing;
        if (normalized == null) showError(R.string.error_invalid_number);
        else if (nickname.isEmpty()) showError(R.string.error_empty_nickname);
        else if (RelayIdentity.isOwnNumber(this, normalized)) showError(R.string.error_own_relay_number);
        else if ((existing = memberRepository.findByPhone(normalized)) != null && existing.active) showError(R.string.error_duplicate_number);
        else {
            int maxMembers = new Prefs(this).getMaxMembers();
            if (memberRepository.isFull(maxMembers)) {
                Toast.makeText(this, getString(R.string.tpl_group_full_admin, memberRepository.countActiveMembers(), maxMembers), Toast.LENGTH_LONG).show();
                return;
            }
            new CommandProcessor(this).addMember(normalized, nickname, getString(R.string.default_added_by_admin));
            finish();
        }
    }

    private void showError(int stringRes) {
        errorView.setText(stringRes);
        errorView.setVisibility(View.VISIBLE);
    }
}
