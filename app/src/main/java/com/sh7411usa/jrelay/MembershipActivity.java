package com.sh7411usa.jrelay;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import com.sh7411usa.jrelay.db.MemberRepository;
import com.sh7411usa.jrelay.model.Member;
import com.sh7411usa.jrelay.sms.CommandProcessor;
import com.sh7411usa.jrelay.sms.PhoneNumberUtils;
import com.sh7411usa.jrelay.util.CsvUtil;
import com.sh7411usa.jrelay.util.UiUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MembershipActivity extends Activity {

    private static final int REQUEST_EXPORT_CSV = 1001;
    private static final int REQUEST_IMPORT_CSV = 1002;

    private MemberRepository memberRepository;
    private CommandProcessor commandProcessor;
    private LinearLayout container;
    private EditText searchInput;
    private String currentQuery = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_membership);
        memberRepository = new MemberRepository(this);
        commandProcessor = new CommandProcessor(this);
        container = findViewById(R.id.container_members);
        searchInput = findViewById(R.id.edit_search);

        findViewById(R.id.button_add_member).setOnClickListener(v ->
                startActivity(new Intent(this, AddMemberActivity.class)));
        findViewById(R.id.button_membership_options).setOnClickListener(this::showOptionsMenu);

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                currentQuery = s.toString();
                renderMembers();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderMembers();
    }

    private void showOptionsMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenuInflater().inflate(R.menu.membership_options_menu, popup.getMenu());
        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.menu_export_csv) {
                startExport();
                return true;
            } else if (id == R.id.menu_import_csv) {
                startImport();
                return true;
            }
            return false;
        });
        popup.show();
    }

    private void startExport() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/csv");
        intent.putExtra(Intent.EXTRA_TITLE, "jrelay_members.csv");
        startActivityForResult(intent, REQUEST_EXPORT_CSV);
    }

    private void startImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/*");
        startActivityForResult(intent, REQUEST_IMPORT_CSV);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        if (requestCode == REQUEST_EXPORT_CSV) {
            exportCsv(uri);
        } else if (requestCode == REQUEST_IMPORT_CSV) {
            importCsv(uri);
        }
    }

    private void exportCsv(Uri uri) {
        List<Member> members = memberRepository.getActiveMembers();
        try (OutputStream out = getContentResolver().openOutputStream(uri);
             OutputStreamWriter writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
            writer.write("phone,nickname\n");
            for (Member m : members) {
                writer.write(CsvUtil.escapeField(m.phoneE164) + "," + CsvUtil.escapeField(m.nickname) + "\n");
            }
            writer.flush();
            Toast.makeText(this, getString(R.string.export_success, members.size()), Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            Toast.makeText(this, R.string.export_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void importCsv(Uri uri) {
        int imported = 0;
        int skipped = 0;
        boolean firstLine = true;
        try (InputStream in = getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                List<String> fields = CsvUtil.parseLine(line);
                String normalized = fields.size() >= 1 ? PhoneNumberUtils.normalize(fields.get(0).trim()) : null;
                String nickname = fields.size() >= 2 ? fields.get(1).trim() : "";

                if (normalized == null) {
                    boolean wasFirstLine = firstLine;
                    firstLine = false;
                    if (wasFirstLine) {
                        continue;
                    }
                    skipped++;
                    continue;
                }
                firstLine = false;

                Member existing = memberRepository.findByPhone(normalized);
                if (existing != null && existing.active) {
                    skipped++;
                    continue;
                }
                commandProcessor.addMember(normalized, nickname, getString(R.string.default_added_by_admin));
                imported++;
            }
            Toast.makeText(this, getString(R.string.import_summary, imported, skipped), Toast.LENGTH_LONG).show();
            renderMembers();
        } catch (IOException e) {
            Toast.makeText(this, R.string.import_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void renderMembers() {
        container.removeAllViews();
        List<Member> allMembers = memberRepository.getActiveMembers();

        String query = currentQuery.trim();
        String queryLower = query.toLowerCase(Locale.US);
        String queryDigits = digitsOnly(query);
        boolean adminMode = queryLower.contains("admin");

        List<Member> filtered = new ArrayList<>();
        for (Member m : allMembers) {
            if (query.isEmpty() || matchesQuery(m, queryLower, queryDigits, adminMode)) {
                filtered.add(m);
            }
        }

        if (filtered.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(allMembers.isEmpty() ? R.string.no_members_yet : R.string.no_search_results);
            container.addView(empty);
            return;
        }

        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < filtered.size(); i++) {
            Member member = filtered.get(i);
            View row = inflater.inflate(R.layout.row_member, container, false);
            TextView nicknameView = row.findViewById(R.id.text_member_nickname);
            TextView phoneView = row.findViewById(R.id.text_member_phone);
            TextView badgeView = row.findViewById(R.id.text_member_badge);

            setHighlightedText(nicknameView, member.nickname, queryLower);
            setHighlightedPhone(phoneView, member.phoneE164, queryDigits);

            StringBuilder badge = new StringBuilder();
            if (member.isAdmin) {
                badge.append(getString(R.string.admin_badge));
            }
            if (member.isMuted) {
                if (badge.length() > 0) {
                    badge.append(" ");
                }
                badge.append(getString(R.string.muted_badge));
            }
            if (adminMode && member.isAdmin) {
                setHighlightedText(badgeView, badge.toString(), "admin");
            } else {
                badgeView.setText(badge.toString());
            }

            long memberId = member.id;
            row.setOnClickListener(v -> {
                Intent intent = new Intent(MembershipActivity.this, MemberDetailActivity.class);
                intent.putExtra(MemberDetailActivity.EXTRA_MEMBER_ID, memberId);
                startActivity(intent);
            });
            container.addView(row);

            if (i < filtered.size() - 1) {
                container.addView(UiUtil.createDivider(this, R.color.divider));
            }
        }
    }

    private boolean matchesQuery(Member member, String queryLower, String queryDigits, boolean adminMode) {
        if (adminMode && member.isAdmin) {
            return true;
        }
        if (!queryLower.isEmpty() && member.nickname.toLowerCase(Locale.US).contains(queryLower)) {
            return true;
        }
        if (!queryDigits.isEmpty() && digitsOnly(member.phoneE164).contains(queryDigits)) {
            return true;
        }
        return false;
    }

    private String digitsOnly(String text) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isDigit(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private void setHighlightedText(TextView view, String text, String queryLower) {
        if (queryLower == null || queryLower.isEmpty()) {
            view.setText(text);
            return;
        }
        int start = text.toLowerCase(Locale.US).indexOf(queryLower);
        if (start < 0) {
            view.setText(text);
            return;
        }
        SpannableString spannable = new SpannableString(text);
        spannable.setSpan(new BackgroundColorSpan(getColor(R.color.search_highlight)),
                start, start + queryLower.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        view.setText(spannable);
    }

    /** phoneE164 is always "+" followed only by digits, so a digit-index match maps to char-index + 1. */
    private void setHighlightedPhone(TextView view, String phoneE164, String queryDigits) {
        if (queryDigits == null || queryDigits.isEmpty()) {
            view.setText(phoneE164);
            return;
        }
        int digitStart = digitsOnly(phoneE164).indexOf(queryDigits);
        if (digitStart < 0) {
            view.setText(phoneE164);
            return;
        }
        int charStart = digitStart + 1;
        int charEnd = charStart + queryDigits.length();
        SpannableString spannable = new SpannableString(phoneE164);
        spannable.setSpan(new BackgroundColorSpan(getColor(R.color.search_highlight)),
                charStart, charEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        view.setText(spannable);
    }
}
