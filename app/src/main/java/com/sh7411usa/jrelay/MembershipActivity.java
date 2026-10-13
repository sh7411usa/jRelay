package com.sh7411usa.jrelay;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import com.sh7411usa.jrelay.db.JoinRequestRepository;
import com.sh7411usa.jrelay.db.MemberRepository;
import com.sh7411usa.jrelay.db.OutboxRepository;
import com.sh7411usa.jrelay.model.Member;
import com.sh7411usa.jrelay.sms.CommandProcessor;
import com.sh7411usa.jrelay.sms.PhoneNumberUtils;
import com.sh7411usa.jrelay.sms.SmsSendService;
import com.sh7411usa.jrelay.sms.SubgroupPlanner;
import com.sh7411usa.jrelay.util.CsvUtil;
import com.sh7411usa.jrelay.util.Prefs;
import com.sh7411usa.jrelay.util.RelayIdentity;
import com.sh7411usa.jrelay.util.UiUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public class MembershipActivity extends BaseActivity {

    private static final int REQUEST_EXPORT_CSV = 1001;
    private static final int REQUEST_IMPORT_CSV = 1002;

    private MemberRepository memberRepository;
    private JoinRequestRepository joinRequestRepository;
    private CommandProcessor commandProcessor;
    private LinearLayout container;
    private View joinRequestsSection;
    private TextView joinRequestsHeader;
    private LinearLayout joinRequestsContainer;
    private TextView subgroupSizesView;
    private TextView membersTitleView;
    private String currentQuery = "";

    // Rows/badges from the last render so a rebuild can restore DPAD focus to the same member.
    private final Map<Long, View> memberRowViews = new HashMap<>();
    private final Map<Long, View> memberBadgeViews = new HashMap<>();
    private final List<Long> renderedMemberIds = new ArrayList<>();
    private Long pendingFocusMemberId;

    private final List<View> joinApproveButtons = new ArrayList<>();
    private final List<View> joinDeclineButtons = new ArrayList<>();
    private final List<String> renderedJoinPhones = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_membership);
        memberRepository = new MemberRepository(this);
        joinRequestRepository = new JoinRequestRepository(this);
        commandProcessor = new CommandProcessor(this);
        container = findViewById(R.id.container_members);
        joinRequestsSection = findViewById(R.id.section_join_requests);
        joinRequestsHeader = findViewById(R.id.text_join_requests_header);
        joinRequestsContainer = findViewById(R.id.container_join_requests);
        subgroupSizesView = findViewById(R.id.text_subgroup_sizes);
        membersTitleView = findViewById(R.id.text_members_section_title);

        findViewById(R.id.button_add_member).setOnClickListener(v ->
                startActivity(new Intent(this, AddMemberActivity.class)));
        findViewById(R.id.button_membership_options).setOnClickListener(this::showOptionsMenu);
        findViewById(R.id.button_suggest_subgroups).setOnClickListener(v -> suggestSubgroupAssignments());
        findViewById(R.id.button_send_rosters).setOnClickListener(v -> sendSubgroupRosters());
        findViewById(R.id.button_merge_subgroups).setOnClickListener(v -> mergeSmallSubgroups());

        ((EditText) findViewById(R.id.edit_search)).addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                currentQuery = s.toString();
                renderMembers();
            }
            @Override public void afterTextChanged(Editable s) { }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderJoinRequests();
        renderMembers();
    }

    private void renderJoinRequests() {
        View focused = getCurrentFocus();
        int focusIndex = joinApproveButtons.indexOf(focused);
        boolean focusDecline = false;
        if (focusIndex < 0) {
            focusIndex = joinDeclineButtons.indexOf(focused);
            focusDecline = focusIndex >= 0;
        }
        String focusPhone = focusIndex >= 0 ? renderedJoinPhones.get(focusIndex) : null;
        joinApproveButtons.clear();
        joinDeclineButtons.clear();
        renderedJoinPhones.clear();
        joinRequestsContainer.removeAllViews();
        List<JoinRequestRepository.JoinRequest> requests = joinRequestRepository.getAll();
        if (requests.isEmpty()) {
            joinRequestsSection.setVisibility(View.GONE);
            if (focusIndex >= 0 && !container.isInTouchMode()) {
                findViewById(R.id.button_suggest_subgroups).requestFocus();
            }
            return;
        }
        joinRequestsSection.setVisibility(View.VISIBLE);
        joinRequestsHeader.setText(getString(R.string.join_requests_header, requests.size()));

        for (int i = 0; i < requests.size(); i++) {
            JoinRequestRepository.JoinRequest request = requests.get(i);
            LinearLayout entry = new LinearLayout(this);
            entry.setOrientation(LinearLayout.VERTICAL);
            entry.setPadding(0, UiUtil.dp(this, 12), 0, UiUtil.dp(this, 12));
            TextView nameView = new TextView(this, null, 0, R.style.TextAppearance_JRelay_RowTitle);
            nameView.setText(request.nickname);
            entry.addView(nameView);
            TextView phoneView = new TextView(this, null, 0, R.style.TextAppearance_JRelay_RowCaption);
            // LTR so the leading "+" of an E.164 number stays in front under Hebrew/Yiddish.
            phoneView.setTextDirection(View.TEXT_DIRECTION_LTR);
            phoneView.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            phoneView.setText(request.phoneE164);
            entry.addView(phoneView);
            joinApproveButtons.add(addButton(entry, R.style.Widget_JRelay_Button_Small,
                    R.string.action_approve, v -> confirmApprove(request)));
            joinDeclineButtons.add(addButton(entry, R.style.Widget_JRelay_Button_Small_Outline,
                    R.string.action_decline, v -> confirmDecline(request)));
            joinRequestsContainer.addView(entry);
            if (i < requests.size() - 1) {
                joinRequestsContainer.addView(UiUtil.createDivider(this, R.color.divider));
            }
            renderedJoinPhones.add(request.phoneE164);
        }

        if (focusIndex >= 0 && !container.isInTouchMode()) {
            int samePhone = renderedJoinPhones.indexOf(focusPhone);
            if (samePhone >= 0) {
                (focusDecline ? joinDeclineButtons : joinApproveButtons).get(samePhone).requestFocus();
            } else {
                joinApproveButtons.get(Math.min(focusIndex, joinApproveButtons.size() - 1)).requestFocus();
            }
        }
    }

    private Button addButton(LinearLayout parent, int style, int text, View.OnClickListener onClick) {
        Button button = new Button(this, null, 0, style);
        button.setText(text);
        button.setOnClickListener(onClick);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = UiUtil.dp(this, 8);
        parent.addView(button, params);
        return button;
    }

    private void confirmApprove(JoinRequestRepository.JoinRequest request) {
        confirm(0, getString(R.string.join_approve_confirm, request.nickname), R.string.action_approve, () -> {
            String msg;
            switch (commandProcessor.approveJoinRequest(request.phoneE164, request.nickname)) {
                case ADDED: msg = getString(R.string.join_approved_toast, request.nickname); break;
                case ALREADY_MEMBER: msg = getString(R.string.join_already_member_toast, request.nickname); break;
                case OWN_NUMBER: msg = getString(R.string.error_own_relay_number); break;
                default: msg = getString(R.string.join_group_full_toast); break;
            }
            toast(msg, Toast.LENGTH_LONG);
            renderJoinRequests();
            renderMembers();
        });
    }

    private void confirmDecline(JoinRequestRepository.JoinRequest request) {
        confirm(0, getString(R.string.join_decline_confirm, request.nickname), R.string.action_decline, () -> {
            commandProcessor.declineJoinRequest(request.phoneE164);
            toast(getString(R.string.join_declined_toast), Toast.LENGTH_SHORT);
            renderJoinRequests();
        });
    }

    private void confirm(int title, CharSequence message, int positive, Runnable onConfirm) {
        AlertDialog.Builder b = new AlertDialog.Builder(this).setMessage(message);
        if (title != 0) b.setTitle(title);
        b.setPositiveButton(positive, (dialog, which) -> onConfirm.run())
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void showOk(int title, CharSequence message) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(message)
                .setPositiveButton(android.R.string.ok, null).show();
    }

    private void toast(CharSequence text, int length) {
        Toast.makeText(this, text, length).show();
    }

    private String withWarning(String detail) {
        return getString(R.string.warning_subgroup_visibility) + "\n\n" + detail;
    }

    private void renderSubgroupSizes() {
        Map<Long, Integer> counts = memberRepository.countMembersPerSubgroup();
        if (counts.isEmpty()) {
            subgroupSizesView.setText(R.string.subgroup_sizes_none);
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Long, Integer> e : new TreeMap<>(counts).entrySet()) {
            if (sb.length() > 0) sb.append("  ");
            sb.append(getString(R.string.subgroup_size_line, e.getKey(), e.getValue()));
        }
        subgroupSizesView.setText(sb.toString());
    }

    private void suggestSubgroupAssignments() {
        List<Long> unassignedIds = new ArrayList<>();
        for (Member m : memberRepository.getUnassignedActiveMembers()) unassignedIds.add(m.id);
        Map<Integer, Integer> sizes = new LinkedHashMap<>();
        for (Map.Entry<Long, Integer> e : memberRepository.countMembersPerSubgroup().entrySet()) {
            sizes.put(e.getKey().intValue(), e.getValue());
        }
        SubgroupPlanner.Plan plan = SubgroupPlanner.planFor(unassignedIds, sizes,
                new Prefs(this).getSubgroupTargetSize());
        String summary = SubgroupPlanner.summarize(plan);
        if (plan.assignments.isEmpty()) {
            showOk(R.string.subgroup_suggest_dialog_title, summary);
            return;
        }
        confirm(R.string.subgroup_suggest_dialog_title, withWarning(summary), R.string.subgroup_suggest_apply_action, () -> {
            LinkedHashSet<Long> affected = new LinkedHashSet<>();
            for (SubgroupPlanner.Assignment a : plan.assignments) {
                memberRepository.assignSubgroup(a.memberId, (long) a.subgroupId);
                affected.add((long) a.subgroupId);
            }
            rosterAndDrain(affected);
            renderMembers();
        });
    }

    // In SMS mode sendRosterFor queues nothing, so callers must not report a roster as sent.
    private void rosterAndDrain(Collection<Long> subgroupIds) {
        CommandProcessor processor = new CommandProcessor(this);
        for (Long subgroupId : subgroupIds) processor.sendRosterFor(subgroupId);
        SmsSendService.start(this);
    }

    private void mergeSmallSubgroups() {
        Map<Integer, List<Long>> subgroupMembers = new TreeMap<>();
        for (Long subgroupId : memberRepository.getDistinctSubgroupIds()) {
            List<Long> ids = new ArrayList<>();
            for (Member m : memberRepository.getActiveSubgroupMembers(subgroupId)) ids.add(m.id);
            subgroupMembers.put(subgroupId.intValue(), ids);
        }
        SubgroupPlanner.Plan plan = SubgroupPlanner.planMerges(
                subgroupMembers, new Prefs(this).getSubgroupTargetSize());
        String summary = SubgroupPlanner.summarize(plan);
        if (plan.assignments.isEmpty()) {
            showOk(R.string.merge_dialog_title, summary.isEmpty() ? getString(R.string.merge_none) : summary);
            return;
        }
        confirm(R.string.merge_dialog_title, withWarning(summary), R.string.merge_apply_action, () -> {
            // Capture source groups BEFORE any write; assignSubgroup erases where a member came from.
            LinkedHashSet<Long> affected = new LinkedHashSet<>();
            LinkedHashSet<Long> destinations = new LinkedHashSet<>();
            for (SubgroupPlanner.Assignment a : plan.assignments) {
                Member before = memberRepository.findById(a.memberId);
                if (before != null && before.subgroupId != null) affected.add(before.subgroupId);
                affected.add((long) a.subgroupId);
                destinations.add((long) a.subgroupId);
            }
            // Checked at confirm time: a queued group row resolves recipients at send time, so
            // merging under it would empty the source. Nothing written yet, so refusing is safe.
            if (new OutboxRepository(this).countUnsentForSubgroups(affected) > 0) {
                toast(getString(R.string.merge_blocked_pending), Toast.LENGTH_LONG);
                return;
            }
            for (SubgroupPlanner.Assignment a : plan.assignments) {
                memberRepository.assignSubgroup(a.memberId, (long) a.subgroupId);
            }
            rosterAndDrain(destinations);
            renderMembers();
        });
    }

    private void sendSubgroupRosters() {
        // Button stays enabled in SMS mode so a DPAD user can still focus it and learn why.
        Prefs prefs = new Prefs(this);
        int refusal = prefs.isStopped() ? R.string.membership_rosters_stopped
                : (prefs.getDeliveryMode() != Prefs.DeliveryMode.GROUP_MMS
                        ? R.string.membership_rosters_group_only : 0);
        if (refusal != 0) {
            showOk(R.string.action_send_rosters, getString(refusal));
            return;
        }
        confirm(R.string.action_send_rosters, getString(R.string.warning_subgroup_visibility),
                R.string.action_send_rosters, () -> {
                    int queued = new CommandProcessor(this).sendSubgroupRosters();
                    toast(queued == 0 ? getString(R.string.roster_none)
                            : getString(R.string.roster_queued, queued), Toast.LENGTH_LONG);
                });
    }

    private void showSubgroupAssignDialog(Member member) {
        List<Long> existingIds = memberRepository.getDistinctSubgroupIds();
        Map<Long, Integer> sizes = memberRepository.countMembersPerSubgroup();
        int targetSize = new Prefs(this).getSubgroupTargetSize();
        List<CharSequence> options = new ArrayList<>();
        List<Long> optionIds = new ArrayList<>();
        for (Long id : existingIds) {
            if (member.subgroupId != null && member.subgroupId.equals(id)) continue;
            int count = sizes.containsKey(id) ? sizes.get(id) : 0;
            // Full groups are not offered: one more fails silently on some carriers.
            if (count >= targetSize) continue;
            options.add(getString(R.string.subgroup_option_existing, id, count));
            optionIds.add(id);
        }
        options.add(getString(R.string.subgroup_option_new));
        optionIds.add(null);
        boolean canClear = member.subgroupId != null;
        if (canClear) options.add(getString(R.string.subgroup_option_clear));

        CharSequence[] items = options.toArray(new CharSequence[0]);
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.subgroup_assign_dialog_title, member.nickname))
                .setItems(items, (dialog, which) -> {
                    if (canClear && which == items.length - 1) {
                        // No roster: the group they left lost someone rather than gaining anyone.
                        memberRepository.assignSubgroup(member.id, null);
                        toast(getString(R.string.subgroup_cleared_toast, member.nickname), Toast.LENGTH_SHORT);
                        renderMembers();
                        return;
                    }
                    Long chosen = optionIds.get(which);
                    long max = 0;
                    for (Long id : existingIds) max = Math.max(max, id);
                    confirmAndAssign(member, chosen != null ? chosen : max + 1);
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void confirmAndAssign(Member member, long subgroupId) {
        confirm(R.string.subgroup_confirm_assign_title,
                withWarning(getString(R.string.subgroup_confirm_assign_detail, member.nickname, subgroupId)),
                android.R.string.ok, () -> {
                    memberRepository.assignSubgroup(member.id, subgroupId);
                    // Re-roster the destination only; the group they left gained no one.
                    rosterAndDrain(Collections.singletonList(subgroupId));
                    toast(getString(R.string.subgroup_assigned_toast, member.nickname, subgroupId), Toast.LENGTH_SHORT);
                    renderMembers();
                });
    }

    private void showOptionsMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenuInflater().inflate(R.menu.membership_options_menu, popup.getMenu());
        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.menu_export_csv) {
                startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE).setType("text/csv")
                        .putExtra(Intent.EXTRA_TITLE, "jrelay_members.csv"), REQUEST_EXPORT_CSV);
                return true;
            } else if (id == R.id.menu_import_csv) {
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE).setType("text/*"), REQUEST_IMPORT_CSV);
                return true;
            }
            return false;
        });
        popup.show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQUEST_EXPORT_CSV) {
            exportCsv(uri);
        } else if (requestCode == REQUEST_IMPORT_CSV) {
            promptImportReportingMode(uri);
        }
    }

    private void promptImportReportingMode(Uri uri) {
        CharSequence[] options = {
                getString(R.string.csv_import_report_usual),
                getString(R.string.csv_import_report_streamlined),
                getString(R.string.csv_import_report_none)
        };
        CommandProcessor.ImportReportingMode[] modes = {
                CommandProcessor.ImportReportingMode.USUAL,
                CommandProcessor.ImportReportingMode.STREAMLINED,
                CommandProcessor.ImportReportingMode.NONE
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.csv_import_report_title)
                .setItems(options, (dialog, which) -> importCsv(uri, modes[which]))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
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
            toast(getString(R.string.export_success, members.size()), Toast.LENGTH_LONG);
        } catch (IOException e) {
            toast(getString(R.string.export_failed), Toast.LENGTH_LONG);
        }
    }

    private void importCsv(Uri uri, CommandProcessor.ImportReportingMode mode) {
        List<String[]> toImport = new ArrayList<>();
        Set<String> stagedPhones = new HashSet<>();
        int skipped = 0;
        boolean firstLine = true;
        int maxMembers = new Prefs(this).getMaxMembers();
        int runningCount = maxMembers > 0 ? memberRepository.countActiveMembers() : 0;
        boolean hitLimit = false;
        try (InputStream in = getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                List<String> fields = CsvUtil.parseLine(line);
                String normalized = fields.size() >= 1 ? PhoneNumberUtils.normalize(fields.get(0).trim()) : null;
                String nickname = fields.size() >= 2 ? fields.get(1).trim() : "";
                if (normalized == null) {
                    // An unparseable first line is a header, not a skipped row.
                    if (!firstLine) skipped++;
                    firstLine = false;
                    continue;
                }
                firstLine = false;
                Member existing;
                if (nickname.isEmpty() || RelayIdentity.isOwnNumber(this, normalized)
                        || ((existing = memberRepository.findByPhone(normalized)) != null && existing.active)
                        || stagedPhones.contains(normalized)) {
                    skipped++;
                    continue;
                }
                if (maxMembers > 0 && runningCount >= maxMembers) {
                    hitLimit = true;
                    skipped++;
                    continue;
                }
                stagedPhones.add(normalized);
                toImport.add(new String[]{normalized, nickname});
                runningCount++;
            }
            int imported = commandProcessor.importMembers(toImport, getString(R.string.default_added_by_admin), mode);
            toast(getString(R.string.import_summary, imported, skipped), Toast.LENGTH_LONG);
            if (hitLimit) toast(getString(R.string.tpl_group_full_admin, maxMembers, maxMembers), Toast.LENGTH_LONG);
            renderMembers();
        } catch (IOException e) {
            toast(getString(R.string.import_failed), Toast.LENGTH_LONG);
        }
    }

    private void renderMembers() {
        // Live focus wins; the pending id covers the return from MemberDetailActivity.
        View focused = getCurrentFocus();
        Long focusId = null;
        boolean focusBadge = false;
        for (Long id : renderedMemberIds) {
            if (memberBadgeViews.get(id) == focused) {
                focusId = id;
                focusBadge = true;
            } else if (memberRowViews.get(id) == focused) {
                focusId = id;
            }
        }
        if (focusId == null) focusId = pendingFocusMemberId;
        pendingFocusMemberId = null;
        int focusIndex = focusId != null ? renderedMemberIds.indexOf(focusId) : -1;
        memberRowViews.clear();
        memberBadgeViews.clear();
        renderedMemberIds.clear();
        container.removeAllViews();
        renderSubgroupSizes();
        List<Member> allMembers = memberRepository.getActiveMembers();
        membersTitleView.setText(getString(R.string.membership_members_count, allMembers.size()));

        String query = currentQuery.trim();
        String queryLower = query.toLowerCase(Locale.US);
        String queryDigits = digitsOnly(query);
        // The English word always works, and so does the localized badge word without its brackets.
        String adminWord = getString(R.string.admin_badge)
                .replaceAll("[()\\[\\]]", "").trim().toLowerCase(Locale.ROOT);
        boolean adminMode = queryLower.contains("admin")
                || (!adminWord.isEmpty() && queryLower.contains(adminWord));
        List<Member> filtered = new ArrayList<>();
        for (Member m : allMembers) {
            if (query.isEmpty() || matchesQuery(m, queryLower, queryDigits, adminMode)) filtered.add(m);
        }
        if (filtered.isEmpty()) {
            TextView empty = new TextView(this, null, 0, R.style.Widget_JRelay_EmptyState);
            empty.setText(allMembers.isEmpty() ? R.string.no_members_yet : R.string.no_search_results);
            container.addView(empty);
            restoreMemberFocus(focusId, focusBadge, focusIndex);
            return;
        }

        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < filtered.size(); i++) {
            Member member = filtered.get(i);
            View row = inflater.inflate(R.layout.row_member, container, false);
            TextView badgeView = row.findViewById(R.id.text_member_badge);
            setHighlightedText(row.findViewById(R.id.text_member_nickname), member.nickname, queryLower);
            setHighlightedPhone(row.findViewById(R.id.text_member_phone), member.phoneE164, queryDigits);

            String badge = member.isAdmin ? getString(R.string.admin_badge) : "";
            if (member.isMuted) badge += (badge.isEmpty() ? "" : " ") + getString(R.string.muted_badge);
            if (badge.isEmpty()) {
                badgeView.setVisibility(View.GONE);
            } else {
                badgeView.setVisibility(View.VISIBLE);
                badgeView.setBackgroundResource(member.isAdmin ? R.drawable.bg_badge_admin : R.drawable.bg_badge_muted);
                badgeView.setTextColor(getColor(member.isAdmin ? R.color.primary : R.color.warning));
                if (adminMode && member.isAdmin) {
                    setHighlightedText(badgeView, badge, adminWord);
                } else {
                    badgeView.setText(badge);
                }
            }

            TextView subgroupBadge = new TextView(this);
            subgroupBadge.setId(View.generateViewId());
            subgroupBadge.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            subgroupBadge.setTextSize(12);
            subgroupBadge.setTypeface(subgroupBadge.getTypeface(), Typeface.BOLD);
            subgroupBadge.setClickable(true);
            subgroupBadge.setFocusable(true);
            boolean assigned = member.subgroupId != null;
            if (assigned) {
                subgroupBadge.setText(getString(R.string.subgroup_row_badge, member.subgroupId));
            } else {
                subgroupBadge.setText(R.string.subgroup_row_unassigned_badge);
            }
            int restingBackground = assigned ? R.drawable.bg_badge_live : R.drawable.bg_stat_tile;
            int restingTextColor = getColor(assigned ? R.color.success : R.color.text_secondary);
            // Green on the focus fill falls short of 4.5:1, so it reads as primary text while focused.
            int focusedTextColor = assigned ? getColor(R.color.text_primary) : restingTextColor;
            subgroupBadge.setTextColor(restingTextColor);
            subgroupBadge.setBackgroundResource(restingBackground);
            subgroupBadge.setPadding(UiUtil.dp(this, 8), UiUtil.dp(this, 4), UiUtil.dp(this, 8), UiUtil.dp(this, 4));
            // Pill drawables have no focused state; setBackgroundResource resets padding, so reapply it.
            subgroupBadge.setOnFocusChangeListener((v, hasFocus) -> {
                v.setBackgroundResource(hasFocus ? R.drawable.focus_highlight : restingBackground);
                v.setPadding(UiUtil.dp(this, 8), UiUtil.dp(this, 4), UiUtil.dp(this, 8), UiUtil.dp(this, 4));
                ((TextView) v).setTextColor(hasFocus ? focusedTextColor : restingTextColor);
            });
            subgroupBadge.setOnClickListener(v -> showSubgroupAssignDialog(member));
            ((LinearLayout) row.findViewById(R.id.layout_member_badges)).addView(subgroupBadge, 0);

            long memberId = member.id;
            row.setOnClickListener(v -> {
                pendingFocusMemberId = memberId;
                startActivity(new Intent(this, MemberDetailActivity.class)
                        .putExtra(MemberDetailActivity.EXTRA_MEMBER_ID, memberId));
            });
            container.addView(row);
            memberRowViews.put(memberId, row);
            memberBadgeViews.put(memberId, subgroupBadge);
            renderedMemberIds.add(memberId);
            if (i < filtered.size() - 1) container.addView(UiUtil.createDivider(this, R.color.divider));
        }
        restoreMemberFocus(focusId, focusBadge, focusIndex);
    }

    // Skipped in touch mode so touch users are not scrolled around.
    private void restoreMemberFocus(Long focusId, boolean badge, int oldIndex) {
        if (focusId == null || container.isInTouchMode()) return;
        Map<Long, View> views = badge ? memberBadgeViews : memberRowViews;
        View target = views.get(focusId);
        if (target == null && !renderedMemberIds.isEmpty()) {
            int index = Math.max(0, Math.min(oldIndex, renderedMemberIds.size() - 1));
            target = views.get(renderedMemberIds.get(index));
        }
        if (target == null) target = findViewById(R.id.button_add_member);
        target.requestFocus();
    }

    private boolean matchesQuery(Member member, String queryLower, String queryDigits, boolean adminMode) {
        return (adminMode && member.isAdmin)
                || (!queryLower.isEmpty() && member.nickname.toLowerCase(Locale.US).contains(queryLower))
                || (!queryDigits.isEmpty() && digitsOnly(member.phoneE164).contains(queryDigits));
    }

    private String digitsOnly(String text) {
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (Character.isDigit(c)) sb.append(c);
        }
        return sb.toString();
    }

    private void setHighlightedText(TextView view, String text, String queryLower) {
        int start = queryLower == null || queryLower.isEmpty() ? -1 : text.toLowerCase(Locale.US).indexOf(queryLower);
        highlight(view, text, start, start < 0 ? 0 : queryLower.length());
    }

    // phoneE164 is "+" then digits only, so a digit-index match maps to char-index + 1.
    private void setHighlightedPhone(TextView view, String phoneE164, String queryDigits) {
        int digitStart = queryDigits == null || queryDigits.isEmpty() ? -1 : digitsOnly(phoneE164).indexOf(queryDigits);
        highlight(view, phoneE164, digitStart < 0 ? -1 : digitStart + 1, digitStart < 0 ? 0 : queryDigits.length());
    }

    private void highlight(TextView view, String text, int start, int length) {
        if (start < 0) {
            view.setText(text);
            return;
        }
        SpannableString spannable = new SpannableString(text);
        spannable.setSpan(new BackgroundColorSpan(getColor(R.color.search_highlight)),
                start, start + length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        view.setText(spannable);
    }
}
