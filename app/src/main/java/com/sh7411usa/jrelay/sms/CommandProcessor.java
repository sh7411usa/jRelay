package com.sh7411usa.jrelay.sms;

import android.content.Context;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.Log;

import com.sh7411usa.jrelay.R;
import com.sh7411usa.jrelay.db.JoinRequestRepository;
import com.sh7411usa.jrelay.db.MemberRepository;
import com.sh7411usa.jrelay.db.MessageRepository;
import com.sh7411usa.jrelay.db.OutboxRepository;
import com.sh7411usa.jrelay.model.Member;
import com.sh7411usa.jrelay.model.MessageRecord;
import com.sh7411usa.jrelay.util.DailyLimitManager;
import com.sh7411usa.jrelay.util.MessageSalt;
import com.sh7411usa.jrelay.util.NotificationHelper;
import com.sh7411usa.jrelay.util.Prefs;
import com.sh7411usa.jrelay.util.RelayIdentity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public class CommandProcessor {

    private static final String TAG = "CommandProcessor";
    private static final long ECHO_WINDOW_MILLIS = 10 * 60 * 1000L;
    private static final long ECHO_WINDOW_SLACK_MILLIS = 60 * 1000L;
    // Members (not admins) may post at most this many group messages per window.
    private static final int POST_RATE_LIMIT = 2;
    private static final long POST_RATE_WINDOW_MILLIS = 10 * 60 * 1000L;

    private final Context context;
    private final MemberRepository memberRepository;
    private final MessageRepository messageRepository;
    private final OutboxRepository outboxRepository;
    private final JoinRequestRepository joinRequestRepository;
    private final Prefs prefs;

    public enum ImportReportingMode { USUAL, STREAMLINED, NONE }

    public enum ApproveResult { ADDED, ALREADY_MEMBER, GROUP_FULL, OWN_NUMBER }

    public CommandProcessor(Context context) {
        this.context = context.getApplicationContext();
        memberRepository = new MemberRepository(this.context);
        messageRepository = new MessageRepository(this.context);
        outboxRepository = new OutboxRepository(this.context);
        joinRequestRepository = new JoinRequestRepository(this.context);
        prefs = new Prefs(this.context);
    }

    public void handleIncoming(String senderE164, String body) {
        handleIncoming(senderE164, body, System.currentTimeMillis());
    }

    /** receivedAtMillis may be up to 48h ago (SmsCatchUp); the echo check is anchored to it. */
    public void handleIncoming(String senderE164, String body, long receivedAtMillis) {
        // Stopped drops the text; Paused handles it and the drain holds what it queues.
        if (prefs.isStopped()) return;
        String trimmed = body == null ? "" : body.trim();
        // An empty body would otherwise be relayed as an empty post to the whole group.
        if (trimmed.isEmpty() || isSelfEcho(senderE164, trimmed, receivedAtMillis)) return;
        if (trimmed.toLowerCase(Locale.ROOT).startsWith("#join")) {
            handleJoinRequest(senderE164, trimmed);
            SmsSendService.start(context);
            return;
        }
        Member sender = memberRepository.findByPhone(senderE164);
        if (sender == null || !sender.active) return;
        messageRepository.log(sender.id, "IN", "RELAY", body);
        String effective = trimmed;
        if (prefs.isBareKeywordsEnabled()) {
            String canonical = MessageIntent.canonicalBareKeyword(trimmed);
            if (canonical != null) effective = canonical;
        }
        // #all is checked before the "#" dispatch; when switched off it falls through as unknown.
        String postBody = prefs.isCommandEnabled(SmsCommand.ALL) ? MessageIntent.stripPostPrefix(effective) : null;
        if (postBody != null) {
            if (!sender.isMuted) handleExplicitPost(sender, postBody);
        } else if (effective.startsWith("#")) {
            handleCommand(sender, effective);
        } else if (!sender.isMuted) {
            relayPlainMessage(sender, effective);
        }
        SmsSendService.start(context);
    }

    public int stopRelay() {
        // Flag first, so enqueue refuses new rows and the discard is not refilled behind it.
        prefs.setStopped(true);
        int discarded = outboxRepository.discardUnsent();
        Log.i(TAG, "Relay stopped; discarded " + discarded + " unsent outbox row(s)");
        return discarded;
    }

    public void startRelay() {
        // Still stopped here, so nothing new can be queued behind this discard.
        int discarded = outboxRepository.discardUnsent();
        Log.i(TAG, "Relay starting; discarded " + discarded + " unsent outbox row(s) left from the stop");
        // Move inbox watermarks to now so texts that arrived while stopped are not relayed late.
        long now = System.currentTimeMillis();
        prefs.setSmsCatchUpSinceMillis(now);
        prefs.setMmsIngestSinceSeconds(now / 1000L);
        prefs.setStopped(false);
        SmsSendService.start(context);
    }

    /** Breaks the loop if the relay's own number is ever a member: drops own-number and echoed texts. */
    private boolean isSelfEcho(String senderE164, String trimmedBody, long receivedAtMillis) {
        if (RelayIdentity.isOwnNumber(context, senderE164)) {
            Log.w(TAG, "Dropped inbound text from the relay's own number");
            return true;
        }
        if (outboxRepository.sentSameBodyRecently(senderE164, MessageSalt.normalizeEchoBody(trimmedBody),
                receivedAtMillis - ECHO_WINDOW_MILLIS, receivedAtMillis + ECHO_WINDOW_SLACK_MILLIS)) {
            Log.w(TAG, "Dropped inbound text that echoes a message the relay just sent to the same number");
            return true;
        }
        return false;
    }

    private void handleCommand(Member sender, String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        SmsCommand command = SmsCommand.match(lower);
        // A switched-off command is answered exactly like one that does not exist.
        if (command == null || !prefs.isCommandEnabled(command)) {
            reply(sender, context.getString(R.string.tpl_unknown_command));
            return;
        }
        switch (command) {
            case COMMANDS: handleCommandsList(sender); break;
            // #help is deliberately distinct from #commands (bare "HELP" maps to #commands).
            case HELP:
                reply(sender, context.getString(sender.isMuted ? R.string.tpl_help_muted : modeRes(prefs.getGroupMode(),
                        R.string.tpl_help_announcement, R.string.tpl_help_reply, R.string.tpl_help_group)));
                break;
            case MUTE:
                boolean mute = lower.equals("#mute");
                memberRepository.setMuted(sender.id, mute);
                reply(sender, context.getString(mute ? R.string.tpl_muted_confirm : R.string.tpl_unmuted_confirm));
                break;
            case STOP: handleStop(sender); break;
            case LIST: handleList(sender); break;
            case NAME: handleNameChange(sender, text); break;
            case ADMIN:
                String message = argOf(text);
                if (!message.isEmpty()) sendToAdminsOnly(sender, message);
                break;
            case ADD: handleAdd(sender, text); break;
            case REMOVE: handleRemove(sender, text); break;
            case TOPIC:
                if (!requireAdmin(sender)) break;
                String newName = argOf(text);
                if (!newName.isEmpty()) changeGroupName(newName, sender.nickname, sender.id);
                break;
            case TO: handleToCommand(sender, text); break;
            case LIMITS: handleLimits(sender); break;
            case OVERRIDE: handleOverride(sender, text); break;
            case MODE: reply(sender, context.getString(modeStatusRes(prefs.getGroupMode()))); break;
            case MODE_SET: handleModeChange(sender, text); break;
            default: break; // JOIN is handled before the member lookup.
        }
    }

    private void handleCommandsList(Member requester) {
        List<SmsCommand> lines = SmsCommand.listLines(prefs.getDisabledCommands(), requester.isAdmin,
                prefs.getGroupMode() == Prefs.GroupMode.REPLY, prefs.getJoinPolicy() != Prefs.JoinPolicy.OFF);
        StringBuilder sb = new StringBuilder(context.getString(R.string.tpl_commands_list_header));
        for (SmsCommand c : lines) sb.append('\n').append(context.getString(c.lineRes));
        reply(requester, sb.toString());
    }

    private static int modeRes(Prefs.GroupMode mode, int announcement, int reply, int group) {
        switch (mode) {
            case ANNOUNCEMENT: return announcement;
            case REPLY: return reply;
            default: return group;
        }
    }

    private static int modeStatusRes(Prefs.GroupMode mode) {
        return modeRes(mode, R.string.tpl_mode_status_announcement, R.string.tpl_mode_status_reply,
                R.string.tpl_mode_status_group);
    }

    /** The last admin can't be removed over SMS: nothing but the app UI could restore an admin. */
    private boolean isLastAdmin(Member m) {
        return m.isAdmin && memberRepository.countActiveAdmins() == 1;
    }

    private boolean requireAdmin(Member sender) {
        if (!sender.isAdmin) reply(sender, context.getString(R.string.tpl_unauthorized));
        return sender.isAdmin;
    }

    private void handleStop(Member sender) {
        if (isLastAdmin(sender)) {
            reply(sender, context.getString(R.string.tpl_last_admin_refused));
            return;
        }
        memberRepository.softRemove(sender.id);
        reply(sender, context.getString(R.string.tpl_removed_you, prefs.getGroupName()));
        if (prefs.isNotifyMemberLeftEnabled()) {
            broadcastExcept(sender.id, context.getString(R.string.tpl_left_other, sender.nickname));
        }
    }

    private void handleList(Member requester) {
        StringBuilder sb = new StringBuilder(prefs.getGroupName()).append("\n\n");
        String sep = "";
        for (Member m : memberRepository.getActiveMembers()) {
            sb.append(sep).append(m.nickname);
            sep = "\n";
            if (m.id == requester.id) sb.append(" ").append(context.getString(R.string.tpl_list_you));
            if (m.isAdmin) sb.append(" ").append(context.getString(R.string.tpl_list_admin));
        }
        reply(requester, sb.toString());
    }

    private void handleNameChange(Member sender, String text) {
        String newNickname = argOf(text);
        if (newNickname.isEmpty()) {
            reply(sender, context.getString(R.string.error_empty_nickname));
            return;
        }
        // Nickname is rendered verbatim as the attribution prefix of every post; block forgeries.
        if (!MessageIntent.isValidNickname(newNickname)) {
            reply(sender, context.getString(R.string.error_invalid_nickname));
            return;
        }
        String oldNickname = sender.nickname;
        boolean changed = !newNickname.equals(oldNickname);
        if (changed) memberRepository.setNickname(sender.id, newNickname);
        reply(sender, context.getString(R.string.tpl_name_changed_confirm, newNickname));
        if (changed && prefs.isNotifyNameChangedEnabled()) {
            broadcastExcept(sender.id, context.getString(R.string.tpl_name_changed_self_other, oldNickname, newNickname));
        }
    }

    public void renameMemberFromApp(Member target, String newNickname, String changedByLabel) {
        String oldNickname = target.nickname;
        memberRepository.setNickname(target.id, newNickname);
        enqueue(target, context.getString(R.string.tpl_name_changed_confirm, newNickname), "SYSTEM");
        if (prefs.isNotifyNameChangedEnabled()) {
            broadcastExcept(target.id, context.getString(R.string.tpl_name_changed_admin_other, changedByLabel, oldNickname, newNickname));
        }
        SmsSendService.start(context);
    }

    private void sendToAdminsOnly(Member sender, String message) {
        String formatted = context.getString(R.string.tpl_admin_relay_prefix, sender.nickname, message);
        enqueueToAdmins(formatted, "ADMIN", sender.id);
        NotificationHelper.showAdminMessage(context, formatted);
    }

    /** Enqueues to every unmuted active admin, skipping the given member ids. */
    private void enqueueToAdmins(String message, String category, long... excludeIds) {
        for (Member admin : memberRepository.getActiveAdmins()) {
            boolean skip = admin.isMuted;
            for (long id : excludeIds) skip |= admin.id == id;
            if (!skip) enqueue(admin, message, category);
        }
    }

    private void handleAdd(Member sender, String text) {
        if (!requireAdmin(sender)) return;
        String rest = argOf(text);
        String[] parts = PhoneNumberUtils.splitLeadingNumberAndRest(rest);
        if (parts == null) parts = PhoneNumberUtils.splitTrailingNumberAndRest(rest);
        String normalized = parts == null ? null : PhoneNumberUtils.normalize(parts[0]);
        if (normalized == null) {
            reply(sender, context.getString(R.string.tpl_invalid_number));
            return;
        }
        String nickname = parts[1].trim();
        if (nickname.isEmpty()) {
            reply(sender, context.getString(R.string.error_empty_nickname));
            return;
        }
        if (RelayIdentity.isOwnNumber(context, normalized)) {
            reply(sender, context.getString(R.string.tpl_add_own_relay_number));
            return;
        }
        Member existing = memberRepository.findByPhone(normalized);
        if (existing != null && existing.active) {
            reply(sender, context.getString(R.string.error_duplicate_number));
            return;
        }
        int maxMembers = prefs.getMaxMembers();
        if (memberRepository.isFull(maxMembers)) {
            reply(sender, context.getString(R.string.tpl_group_full_admin, memberRepository.countActiveMembers(), maxMembers));
            return;
        }
        addMember(normalized, nickname, sender.nickname);
    }

    /** The one place a member row is created or revived, so the one place the own number is refused (returns null). */
    private Member insertOrReactivateMember(String normalizedPhone, String nickname, String addedBy, String ownNumber) {
        if (ownNumber != null && ownNumber.equals(PhoneNumberUtils.normalize(normalizedPhone))) {
            Log.w(TAG, "Refused to add the relay's own number as a member");
            return null;
        }
        Member existing = memberRepository.findByPhone(normalizedPhone);
        long id;
        if (existing != null && !existing.active) {
            memberRepository.reactivate(existing.id, nickname, addedBy);
            id = existing.id;
        } else {
            id = memberRepository.insert(normalizedPhone, nickname, false, addedBy);
        }
        return memberRepository.findById(id);
    }

    private void sendWelcome(Member newMember, String welcome) {
        enqueue(newMember, withReplyHint(welcome), "SYSTEM");
    }

    public void addMember(String normalizedPhone, String nickname, String addedByLabel) {
        Member newMember = insertOrReactivateMember(normalizedPhone, nickname, addedByLabel, RelayIdentity.ownNumber(context));
        if (newMember == null) return;
        // Every approval path lands here, so clear the pending join request here.
        joinRequestRepository.delete(normalizedPhone);
        if (prefs.isAddedReportingEnabled()) {
            sendWelcome(newMember, context.getString(R.string.tpl_added_you, addedByLabel, prefs.getGroupName()));
            broadcastExcept(newMember.id, context.getString(R.string.tpl_added_other, addedByLabel, nickname));
        }
        SmsSendService.start(context);
    }

    public int importMembers(List<String[]> phoneNicknamePairs, String addedByLabel, ImportReportingMode mode) {
        List<Member> preExistingMembers = memberRepository.getActiveMembers();
        String ownNumber = RelayIdentity.ownNumber(context);
        int count = 0;
        for (String[] pair : phoneNicknamePairs) {
            Member newMember = insertOrReactivateMember(pair[0], pair[1], addedByLabel, ownNumber);
            if (newMember == null) continue;
            count++;
            if (mode == ImportReportingMode.NONE) continue;
            sendWelcome(newMember, context.getString(R.string.tpl_added_you, addedByLabel, prefs.getGroupName()));
            if (mode == ImportReportingMode.USUAL) {
                broadcastExcept(newMember.id, context.getString(R.string.tpl_added_other, addedByLabel, pair[1]));
            }
        }
        if (mode == ImportReportingMode.STREAMLINED && count > 0) {
            String message = context.getString(R.string.tpl_csv_import_streamlined_notice, addedByLabel, count);
            for (Member m : preExistingMembers) {
                if (!m.isMuted) enqueue(m, message, "SYSTEM");
            }
        }
        SmsSendService.start(context);
        return count;
    }

    private void handleRemove(Member sender, String text) {
        if (!requireAdmin(sender)) return;
        String rest = argOf(text);
        Member target = rest.isEmpty() ? null : resolveMemberTolerant(rest);
        if (target == null) return;
        if (isLastAdmin(target)) {
            reply(sender, context.getString(R.string.tpl_last_admin_refused));
            return;
        }
        removeMember(target, sender.nickname);
    }

    /**
     * Silently removes the relay's own number from members, bypassing last-admin protection: as a
     * member the relay loops every post back to itself. Returns the nickname removed, or null.
     */
    public String purgeRelaySelfMember() {
        String own = RelayIdentity.ownNumber(context);
        if (own == null) return null;
        Member self = memberRepository.findByPhone(own);
        if (self == null || !self.active) return null;
        memberRepository.softRemove(self.id); // also clears is_admin
        Log.w(TAG, "Removed this relay phone's own number from members (member id " + self.id + ")");
        return self.nickname == null ? "" : self.nickname;
    }

    public void removeMember(Member target, String removedByLabel) {
        memberRepository.softRemove(target.id);
        enqueue(target, context.getString(R.string.tpl_removed_you, prefs.getGroupName()), "SYSTEM");
        if (prefs.isNotifyMemberRemovedEnabled()) {
            broadcastExcept(target.id, context.getString(R.string.tpl_removed_other, removedByLabel, target.nickname));
        }
        SmsSendService.start(context);
    }

    /** excludeId: -1 notifies every active member. */
    public void changeGroupName(String newName, String changedByLabel, long excludeId) {
        prefs.setGroupName(newName);
        if (prefs.isNotifyGroupRenamedEnabled()) {
            broadcastExcept(excludeId, context.getString(R.string.tpl_group_name_changed, changedByLabel, newName));
        }
        SmsSendService.start(context);
    }

    private void handleLimits(Member requester) {
        if (!requireAdmin(requester)) return;
        DailyLimitManager.Status status = new DailyLimitManager(context).groupStatus();
        if (!status.enabled) {
            reply(requester, context.getString(R.string.tpl_limits_disabled));
            return;
        }
        reply(requester, context.getString(R.string.tpl_limits_status,
                status.used, status.limit, status.remaining(), resetTimeLabel(status.resetAtMillis)));
    }

    private void handleOverride(Member sender, String text) {
        if (!requireAdmin(sender)) return;
        String rest = argOf(text);
        DailyLimitManager limitManager = new DailyLimitManager(context);
        if (rest.isEmpty()) {
            limitManager.overrideGroup();
            reply(sender, context.getString(R.string.tpl_override_group_done));
            return;
        }
        Member target = resolveMemberTolerant(rest);
        if (target == null) return;
        reply(sender, context.getString(limitManager.overrideMember(target)
                ? R.string.tpl_override_member_done : R.string.tpl_override_member_no_limit, target.nickname));
    }

    private String resetTimeLabel(long resetAtMillis) {
        return DateFormat.format("h:mm a", resetAtMillis).toString();
    }

    private void handleModeChange(Member sender, String text) {
        if (!requireAdmin(sender)) return;
        Prefs.GroupMode newMode;
        switch (argOf(text).toLowerCase(Locale.ROOT)) {
            case "announcement": newMode = Prefs.GroupMode.ANNOUNCEMENT; break;
            case "group": newMode = Prefs.GroupMode.GROUP; break;
            case "reply": newMode = Prefs.GroupMode.REPLY; break;
            default:
                reply(sender, context.getString(R.string.tpl_mode_invalid));
                return;
        }
        setGroupMode(newMode, sender.nickname, sender.id);
        reply(sender, context.getString(modeStatusRes(newMode)));
    }

    /** excludeId: -1 notifies every active member. */
    public void setGroupMode(Prefs.GroupMode newMode, String changedByLabel, long excludeId) {
        prefs.setGroupMode(newMode);
        if (prefs.isNotifyModeChangedEnabled()) {
            int res = modeRes(newMode, R.string.tpl_mode_changed_announcement, R.string.tpl_mode_changed_reply,
                    R.string.tpl_mode_changed_group);
            broadcastExcept(excludeId, context.getString(res, changedByLabel));
        }
        SmsSendService.start(context);
    }

    private void handleJoinRequest(String senderE164, String text) {
        Member existing = memberRepository.findByPhone(senderE164);
        if (existing != null && existing.active) {
            reply(existing, context.getString(R.string.tpl_join_already_member));
            return;
        }
        messageRepository.log(null, "IN", "SYSTEM", text);
        Prefs.JoinPolicy policy = prefs.getJoinPolicy();
        if (policy == Prefs.JoinPolicy.OFF) return;
        String nickname = argOf(text);
        if (!nickname.isEmpty() && !MessageIntent.isValidNickname(nickname)) {
            replyToNumber(senderE164, context.getString(R.string.error_invalid_nickname));
            return;
        }
        // Capacity before the name-required check, so a full group doesn't cost two texts.
        if (memberRepository.isFull(prefs.getMaxMembers())) {
            replyToNumber(senderE164, prefs.getGroupFullMessage());
            return;
        }
        if (nickname.isEmpty()) {
            replyToNumber(senderE164, context.getString(R.string.tpl_join_name_required));
            return;
        }
        if (policy == Prefs.JoinPolicy.ALLOW) {
            selfJoin(senderE164, nickname);
        } else {
            requestJoinApproval(senderE164, nickname);
        }
    }

    public void selfJoin(String normalizedPhone, String nickname) {
        Member newMember = insertOrReactivateMember(normalizedPhone, nickname, nickname, RelayIdentity.ownNumber(context));
        if (newMember == null) return;
        if (prefs.isAddedReportingEnabled()) {
            sendWelcome(newMember, context.getString(R.string.tpl_added_you_self, prefs.getGroupName()));
            broadcastExcept(newMember.id, context.getString(R.string.tpl_joined_other, nickname));
        }
        SmsSendService.start(context);
    }

    public ApproveResult approveJoinRequest(String phoneE164, String nickname) {
        if (RelayIdentity.isOwnNumber(context, phoneE164)) {
            joinRequestRepository.delete(phoneE164);
            return ApproveResult.OWN_NUMBER;
        }
        Member existing = memberRepository.findByPhone(phoneE164);
        if (existing != null && existing.active) {
            joinRequestRepository.delete(phoneE164);
            return ApproveResult.ALREADY_MEMBER;
        }
        // Keep the request when full: it can be approved once there is room.
        if (memberRepository.isFull(prefs.getMaxMembers())) return ApproveResult.GROUP_FULL;
        addMember(phoneE164, nickname, context.getString(R.string.join_approved_by_app));
        return ApproveResult.ADDED;
    }

    /** Deliberately sends no text. */
    public void declineJoinRequest(String phoneE164) {
        joinRequestRepository.delete(phoneE164);
    }

    private void requestJoinApproval(String senderE164, String nickname) {
        // Stored first so the in-app list has it even if an admin text or notification fails.
        joinRequestRepository.upsert(senderE164, nickname);
        String adminMessage = context.getString(R.string.tpl_join_request_admin, nickname, senderE164,
                "#add " + senderE164 + " " + nickname);
        enqueueToAdmins(adminMessage, "SYSTEM");
        NotificationHelper.showAdminMessage(context, adminMessage);
        replyToNumber(senderE164, context.getString(R.string.tpl_join_request_sent));
    }

    private void replyToNumber(String phoneE164, String message) {
        enqueueAndLog(null, phoneE164, message, null, "SYSTEM", 0L, false);
    }

    public void alertAdminsOfFailures(Member target, int failedCount) {
        String formatted = context.getString(R.string.tpl_failure_alert, target.nickname, failedCount);
        // Never route an alert about an admin's own number through that admin: its failure would re-trigger the alert.
        alertAdmins(formatted, target.id);
    }

    /** Not yet wired: SentReceiver only calls the per-member path, which never fires for group-MMS rows. */
    public void alertAdminsOfGroupFailure(long subgroupId) {
        alertAdmins(context.getString(R.string.tpl_group_failure_alert, subgroupId));
    }

    private void alertAdmins(String formatted, long... excludeIds) {
        enqueueToAdmins(formatted, "ADMIN", excludeIds);
        NotificationHelper.showAdminMessage(context, formatted);
        SmsSendService.start(context);
    }

    /** Ignores mute; clears the reply-target pointer so a Reply Mode reply goes to admins. */
    public void sendDirectMessage(Member target, String body) {
        enqueue(target, context.getString(R.string.tpl_dm_prefix, body), "DM");
        memberRepository.setLastPostReceivedIdForAll(Collections.singletonList(target.id), null);
        SmsSendService.start(context);
    }

    /** Ignores mute; clears every recipient's reply-target pointer. */
    public void broadcastToGroup(String body) {
        String formatted = context.getString(R.string.tpl_dm_prefix, body);
        BroadcastPlan plan = planBroadcastRecipients(prefs.getDeliveryMode() == Prefs.DeliveryMode.GROUP_MMS);
        List<Long> recipientIds = new ArrayList<>();
        for (Map.Entry<Long, List<Member>> group : plan.bySubgroup.entrySet()) {
            enqueueGroupAndLog(group.getKey(), formatted, "ADMIN", 0L, true, group.getValue());
            for (Member m : group.getValue()) recipientIds.add(m.id);
        }
        for (Member m : plan.individuals) {
            enqueue(m, formatted, "ADMIN");
            recipientIds.add(m.id);
        }
        memberRepository.setLastPostReceivedIdForAll(recipientIds, null);
        SmsSendService.start(context);
    }

    /** {groupMessages, individualTexts, peopleReached} for broadcastToGroup right now. */
    public int[] planBroadcast() {
        if (prefs.getDeliveryMode() != Prefs.DeliveryMode.GROUP_MMS) {
            int active = memberRepository.countActiveMembers();
            return new int[]{0, active, active};
        }
        BroadcastPlan plan = planBroadcastRecipients(true);
        int reached = plan.individuals.size();
        for (List<Member> group : plan.bySubgroup.values()) reached += group.size();
        return new int[]{plan.bySubgroup.size(), plan.individuals.size(), reached};
    }

    private static final class BroadcastPlan {
        final Map<Long, List<Member>> bySubgroup = new TreeMap<>();
        final List<Member> individuals = new ArrayList<>();
    }

    /**
     * Partitions active members: in GROUP_MMS, unmuted sub-group members go to their group row
     * (matching getSubgroupMembers at send time), everyone else gets an individual text.
     */
    private BroadcastPlan planBroadcastRecipients(boolean groupMms) {
        BroadcastPlan plan = new BroadcastPlan();
        for (Member m : memberRepository.getActiveMembers()) {
            if (groupMms && m.subgroupId != null && !m.isMuted) {
                plan.bySubgroup.computeIfAbsent(m.subgroupId, k -> new ArrayList<>()).add(m);
            } else {
                plan.individuals.add(m);
            }
        }
        return plan;
    }

    private void handleExplicitPost(Member sender, String body) {
        if (prefs.getGroupMode() == Prefs.GroupMode.ANNOUNCEMENT && !sender.isAdmin) {
            sendToAdminsOnly(sender, body);
        } else {
            postToGroup(sender, body, null);
        }
    }

    private void relayPlainMessage(Member sender, String body) {
        Prefs.GroupMode mode = prefs.getGroupMode();
        if (mode == Prefs.GroupMode.ANNOUNCEMENT && !sender.isAdmin) {
            sendToAdminsOnly(sender, body);
        } else if (mode == Prefs.GroupMode.REPLY) {
            handleReply(sender, body);
        } else {
            postToGroup(sender, body, null);
        }
    }

    /** A message written inside sub-group threadSubgroupId's MMS thread; bridged to everyone else. */
    public void handleThreadMessage(Member sender, long threadSubgroupId, String body) {
        if (prefs.isStopped()) return;
        // In SMS mode there is no thread, so bridging would duplicate what members just read.
        if (prefs.getDeliveryMode() != Prefs.DeliveryMode.GROUP_MMS) return;
        if (sender == null || !sender.active) return;
        String trimmed = body == null ? "" : body.trim();
        if (trimmed.isEmpty()) return;
        messageRepository.log(sender.id, "IN", "RELAY", body);
        if (sender.isMuted) return;
        // Bare keywords are ordinary words in-thread; '#' commands in front of an audience are dropped.
        if (trimmed.startsWith("#")) return;
        // Announcement mode: the thread already saw it; don't spend texts forwarding to admins.
        if (prefs.getGroupMode() == Prefs.GroupMode.ANNOUNCEMENT && !sender.isAdmin) return;
        postToGroup(sender, trimmed, threadSubgroupId);
    }

    private String withReplyHint(String welcome) {
        return prefs.getGroupMode() == Prefs.GroupMode.REPLY
                ? welcome + context.getString(R.string.tpl_welcome_reply_hint) : welcome;
    }

    /** alreadyDeliveredSubgroupId: the thread's sub-group for an in-thread post, null for a direct SMS. */
    private void postToGroup(Member sender, String body, Long alreadyDeliveredSubgroupId) {
        DailyLimitManager limitManager = new DailyLimitManager(context);
        // In-thread posts drop silently at the cap: a reply per thread message would be unbounded.
        boolean replyOnLimit = alreadyDeliveredSubgroupId == null;
        if (!sender.isAdmin && messageRepository.countRelayedForMemberSince(sender.id,
                System.currentTimeMillis() - POST_RATE_WINDOW_MILLIS) >= POST_RATE_LIMIT) {
            if (replyOnLimit) reply(sender, context.getString(R.string.tpl_post_rate_limited));
            return;
        }
        if (limitBlocked(sender, limitManager.groupStatus(), R.string.tpl_group_limit_blocked, replyOnLimit)
                || limitBlocked(sender, limitManager.memberStatus(sender), R.string.tpl_individual_limit_blocked, replyOnLimit)) {
            return;
        }
        // Sanitize so an injected newline or forged nickname can't fake a second attribution line.
        String formatted = context.getString(R.string.tpl_relay_prefix,
                MessageIntent.sanitizeNicknameForRender(sender.nickname), MessageIntent.sanitizeRelayBody(body));
        formatted = MessageSalt.applyEnqueueTime(prefs, sender.phoneE164, formatted);
        long postLogId = messageRepository.log(sender.id, "IN", "RELAYED", body);
        if (prefs.getDeliveryMode() == Prefs.DeliveryMode.GROUP_MMS) {
            postToSubgroups(sender, formatted, postLogId, alreadyDeliveredSubgroupId);
        } else {
            broadcastExcept(sender.id, formatted, OutboxRepository.CATEGORY_RELAY, postLogId);
        }
    }

    private boolean limitBlocked(Member sender, DailyLimitManager.Status status, int res, boolean replyOnLimit) {
        if (!status.isExhausted()) return false;
        if (replyOnLimit) {
            reply(sender, context.getString(res, status.used, status.limit, resetTimeLabel(status.resetAtMillis)));
        }
        return true;
    }

    /**
     * Sends every sub-group a roster of only its own members (privacy boundary). Unsalted (salt
     * would force UCS-2 on the longest message) and "SYSTEM" with no hold, so it's never coalesced.
     */
    public int sendSubgroupRosters() {
        int queued = 0;
        for (Long subgroupId : memberRepository.getDistinctSubgroupIds()) {
            if (sendRosterFor(subgroupId)) queued++;
        }
        return queued;
    }

    /** Includes muted members: they are still participants of the real MMS thread. */
    public boolean sendRosterFor(long subgroupId) {
        // Single chokepoint: in SMS mode a roster would be an unwanted group MMS.
        if (prefs.getDeliveryMode() != Prefs.DeliveryMode.GROUP_MMS) return false;
        List<Member> members = memberRepository.getActiveSubgroupMembers(subgroupId);
        if (members.isEmpty()) return false;
        String body = RosterComposer.compose(context.getString(R.string.roster_header), prefs.getGroupName(),
                context.getString(R.string.roster_footer), members);
        outboxRepository.deletePendingRosters(subgroupId);
        // False when dropped because the relay is stopped.
        return outboxRepository.enqueueGroup(subgroupId, body, "SYSTEM", 0L, false);
    }

    /** One group row per eligible sub-group plus individual rows for unassigned unmuted members; neither half may be dropped. */
    private void postToSubgroups(Member sender, String formatted, long postLogId, Long alreadyDeliveredSubgroupId) {
        Set<Long> activeSubgroupIds = new LinkedHashSet<>(memberRepository.getDistinctSubgroupIds());
        List<Long> unassignedIds = new ArrayList<>();
        for (Member m : memberRepository.getUnassignedActiveUnmutedMembers()) unassignedIds.add(m.id);
        SubgroupRouter.OutboundPlan plan = SubgroupRouter.routePost(sender.id, alreadyDeliveredSubgroupId,
                activeSubgroupIds, unassignedIds);
        long holdUntil = relayHoldUntil();
        List<Long> postRecipientIds = new ArrayList<>();
        for (Long subgroupId : plan.subgroupIdsToRelay) {
            List<Member> subgroupMembers = memberRepository.getSubgroupMembers(subgroupId);
            enqueueGroupAndLog(subgroupId, formatted, OutboxRepository.CATEGORY_RELAY, holdUntil, true, subgroupMembers);
            for (Member m : subgroupMembers) postRecipientIds.add(m.id);
        }
        for (Long memberId : plan.individualMemberIds) {
            Member m = memberRepository.findById(memberId);
            if (m == null) continue;
            enqueue(m, formatted, OutboxRepository.CATEGORY_RELAY);
            postRecipientIds.add(m.id);
        }
        if (!postRecipientIds.isEmpty()) memberRepository.setLastPostReceivedIdForAll(postRecipientIds, postLogId);
    }

    private void handleReply(Member sender, String body) {
        Member target = resolveReplyTarget(sender);
        if (target == null) {
            sendToAdminsOnly(sender, body);
            reply(sender, context.getString(R.string.tpl_reply_no_target));
        } else {
            deliverReply(sender, target, body);
        }
    }

    /** The author of sender's last-received post, if still active, not the sender, and within the reply window. */
    private Member resolveReplyTarget(Member sender) {
        Long postId = sender.lastPostReceivedId;
        if (postId == null) return null;
        MessageRecord record = messageRepository.getById(postId);
        if (record == null || record.memberId == null) return null;
        Member author = memberRepository.findById(record.memberId);
        if (author == null || !author.active || author.id == sender.id) return null;
        return MessageIntent.isWithinReplyWindow(record.timestamp, System.currentTimeMillis(), prefs.getReplyWindowHours())
                ? author : null;
    }

    private void deliverReply(Member sender, Member target, String body) {
        // Sanitize once for both the target delivery and the admin copy.
        String safeSenderNickname = MessageIntent.sanitizeNicknameForRender(sender.nickname);
        String safeBody = MessageIntent.sanitizeRelayBody(body);
        if (!target.isMuted) {
            enqueue(target, context.getString(R.string.tpl_reply_prefix, safeSenderNickname, safeBody), "REPLY");
        }
        if (prefs.isCopyRepliesToAdmins()) {
            enqueueToAdmins(context.getString(R.string.tpl_reply_copy_admin, safeSenderNickname, target.nickname, safeBody),
                    "ADMIN", sender.id, target.id);
        }
    }

    private void handleToCommand(Member sender, String text) {
        if (prefs.getGroupMode() != Prefs.GroupMode.REPLY) {
            reply(sender, context.getString(R.string.tpl_to_wrong_mode));
            return;
        }
        String rest = text.substring(3).trim();
        if (rest.isEmpty()) {
            reply(sender, context.getString(R.string.tpl_to_usage));
            return;
        }
        // Nicknames may contain spaces: keep the longest word-prefix that resolves to a member.
        String[] words = rest.split("\\s+");
        String bestCandidate = null;
        Member bestMatch = null;
        int bestWordCount = 0;
        StringBuilder candidateBuilder = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (i > 0) candidateBuilder.append(' ');
            String candidate = candidateBuilder.append(words[i]).toString();
            Member match = resolveMemberTolerant(candidate);
            if (match != null) {
                bestCandidate = candidate;
                bestMatch = match;
                bestWordCount = i + 1;
            }
        }
        if (bestMatch == null) {
            reply(sender, context.getString(R.string.tpl_to_not_found, rest));
            return;
        }
        String message = TextUtils.join(" ", Arrays.asList(words).subList(bestWordCount, words.length)).trim();
        if (message.isEmpty()) {
            reply(sender, context.getString(R.string.tpl_to_usage));
        } else if (bestMatch.id == sender.id) {
            reply(sender, context.getString(R.string.tpl_to_not_found, bestCandidate));
        } else {
            deliverReply(sender, bestMatch, message);
        }
    }

    /**
     * Strict phone match first, then active nickname. Phone-first stops a phone-shaped nickname from
     * shadowing the real number owner (e.g. #remove hitting the wrong member).
     */
    private Member resolveMemberTolerant(String candidate) {
        String normalized = PhoneNumberUtils.normalizeStrict(candidate);
        if (normalized != null) {
            Member byPhone = memberRepository.findByPhone(normalized);
            if (byPhone != null && byPhone.active) return byPhone;
        }
        return memberRepository.findActiveByNickname(candidate);
    }

    private void broadcastExcept(long excludeId, String message) {
        broadcastExcept(excludeId, message, "SYSTEM", null);
    }

    private void broadcastExcept(long excludeId, String message, String category, Long postLogId) {
        List<Member> recipients = memberRepository.getActiveRecipientsExcept(excludeId);
        boolean trackPost = postLogId != null && OutboxRepository.CATEGORY_RELAY.equals(category);
        List<Long> postRecipientIds = trackPost ? new ArrayList<>(recipients.size()) : null;
        for (Member m : recipients) {
            enqueue(m, message, category);
            if (trackPost) postRecipientIds.add(m.id);
        }
        if (trackPost) memberRepository.setLastPostReceivedIdForAll(postRecipientIds, postLogId);
    }

    private void reply(Member recipient, String message) {
        enqueue(recipient, message, "COMMAND");
    }

    private long relayHoldUntil() {
        return prefs.isCoalescingEnabled() ? System.currentTimeMillis() + prefs.getCoalesceWindowSeconds() * 1000L : 0L;
    }

    private void enqueue(Member recipient, String message, String category) {
        boolean relay = OutboxRepository.CATEGORY_RELAY.equals(category);
        enqueueAndLog(recipient.id, recipient.phoneE164, message, category, category, relay ? relayHoldUntil() : 0L, relay);
    }

    /** Logs to the feed only if queued: while stopped the outbox drops it and the feed must not show it sent. */
    private void enqueueAndLog(Long memberId, String phoneE164, String message, String category,
                               String logCategory, long holdUntil, boolean applySalt) {
        if (outboxRepository.enqueue(memberId, phoneE164, message, category, holdUntil, applySalt)) {
            messageRepository.log(memberId, "OUT", logCategory, message);
        }
    }

    private void enqueueGroupAndLog(long subgroupId, String message, String category, long holdUntil,
                                    boolean applySalt, List<Member> logFor) {
        if (!outboxRepository.enqueueGroup(subgroupId, message, category, holdUntil, applySalt)) return;
        for (Member m : logFor) messageRepository.log(m.id, "OUT", category, message);
    }

    /** Splits on any whitespace so "#admin\nmessage" still yields the argument. Returns it trimmed. */
    private String argOf(String text) {
        String[] parts = text.split("\\s+", 2);
        return parts.length > 1 ? parts[1].trim() : "";
    }
}
