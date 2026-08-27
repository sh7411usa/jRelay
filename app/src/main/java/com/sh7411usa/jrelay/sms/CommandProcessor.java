package com.sh7411usa.jrelay.sms;

import android.content.Context;

import com.sh7411usa.jrelay.R;
import com.sh7411usa.jrelay.db.MemberRepository;
import com.sh7411usa.jrelay.db.MessageRepository;
import com.sh7411usa.jrelay.db.OutboxRepository;
import com.sh7411usa.jrelay.model.Member;
import com.sh7411usa.jrelay.util.NotificationHelper;
import com.sh7411usa.jrelay.util.Prefs;

import java.util.List;

public class CommandProcessor {

    private final Context context;
    private final MemberRepository memberRepository;
    private final MessageRepository messageRepository;
    private final OutboxRepository outboxRepository;
    private final Prefs prefs;

    public CommandProcessor(Context context) {
        this.context = context.getApplicationContext();
        memberRepository = new MemberRepository(this.context);
        messageRepository = new MessageRepository(this.context);
        outboxRepository = new OutboxRepository(this.context);
        prefs = new Prefs(this.context);
    }

    /** Entry point for an inbound SMS from an already-normalized sender number. */
    public void handleIncoming(String senderE164, String body) {
        Member sender = memberRepository.findByPhone(senderE164);
        if (sender == null || !sender.active) {
            return;
        }

        String trimmed = body == null ? "" : body.trim();
        messageRepository.log(sender.id, "IN", trimmed.startsWith("#") ? "COMMAND" : "RELAY", body);
        if (trimmed.startsWith("#")) {
            handleCommand(sender, trimmed);
        } else if (!sender.isMuted) {
            relayPlainMessage(sender, trimmed);
        }

        SmsSendService.start(context);
    }

    private void handleCommand(Member sender, String text) {
        String lower = text.toLowerCase();
        if (lower.equals("#commands")) {
            handleCommandsList(sender);
        } else if (lower.equals("#mute")) {
            memberRepository.setMuted(sender.id, true);
            reply(sender, context.getString(R.string.tpl_muted_confirm));
        } else if (lower.equals("#unmute")) {
            memberRepository.setMuted(sender.id, false);
            reply(sender, context.getString(R.string.tpl_unmuted_confirm));
        } else if (lower.equals("#stop")) {
            handleStop(sender);
        } else if (lower.equals("#list")) {
            handleList(sender);
        } else if (lower.startsWith("#name")) {
            handleNameChange(sender, text);
        } else if (lower.startsWith("#rename")) {
            handleAdminRename(sender, text);
        } else if (lower.startsWith("#admin")) {
            handleAdminMessage(sender, text);
        } else if (lower.startsWith("#add")) {
            handleAdd(sender, text);
        } else if (lower.startsWith("#remove")) {
            handleRemove(sender, text);
        } else if (lower.startsWith("#topic")) {
            handleTopicChange(sender, text);
        } else {
            reply(sender, context.getString(R.string.tpl_unknown_command));
        }
    }

    private void handleCommandsList(Member requester) {
        StringBuilder sb = new StringBuilder(context.getString(R.string.tpl_commands_list_common));
        if (requester.isAdmin) {
            sb.append(context.getString(R.string.tpl_commands_list_admin_extra));
        }
        reply(requester, sb.toString());
    }

    private void handleStop(Member sender) {
        memberRepository.softRemove(sender.id);
        String groupName = prefs.getGroupName();
        reply(sender, context.getString(R.string.tpl_removed_you, groupName));
        broadcastExcept(sender.id, context.getString(R.string.tpl_left_other, sender.nickname));
    }

    private void handleList(Member requester) {
        List<Member> members = memberRepository.getActiveMembers();
        StringBuilder sb = new StringBuilder();
        sb.append(prefs.getGroupName()).append("\n\n");
        boolean first = true;
        for (Member m : members) {
            if (!first) {
                sb.append("\n");
            }
            first = false;
            sb.append(m.nickname);
            if (m.id == requester.id) {
                sb.append(" ").append(context.getString(R.string.tpl_list_you));
            }
            if (m.isAdmin) {
                sb.append(" ").append(context.getString(R.string.tpl_list_admin));
            }
        }
        reply(requester, sb.toString());
    }

    private void handleNameChange(Member sender, String text) {
        String newNickname = stripLeadingWord(text).trim();
        if (newNickname.isEmpty()) {
            reply(sender, context.getString(R.string.error_empty_nickname));
            return;
        }
        if (newNickname.equals(sender.nickname)) {
            reply(sender, context.getString(R.string.tpl_name_changed_confirm, newNickname));
            return;
        }
        String oldNickname = sender.nickname;
        memberRepository.setNickname(sender.id, newNickname);
        reply(sender, context.getString(R.string.tpl_name_changed_confirm, newNickname));
        broadcastExcept(sender.id, context.getString(R.string.tpl_name_changed_self_other, oldNickname, newNickname));
    }

    private void handleAdminRename(Member sender, String text) {
        if (!sender.isAdmin) {
            reply(sender, context.getString(R.string.tpl_unauthorized));
            return;
        }
        String rest = stripLeadingWord(text).trim();
        String[] parts = PhoneNumberUtils.splitLeadingNumberAndRest(rest);
        if (parts == null) {
            reply(sender, context.getString(R.string.tpl_rename_usage));
            return;
        }
        String normalized = PhoneNumberUtils.normalize(parts[0]);
        String newNickname = parts[1].trim();
        if (normalized == null || newNickname.isEmpty()) {
            reply(sender, context.getString(R.string.tpl_rename_usage));
            return;
        }
        Member target = memberRepository.findByPhone(normalized);
        if (target == null || !target.active) {
            reply(sender, context.getString(R.string.tpl_member_not_found));
            return;
        }
        String oldNickname = target.nickname;
        memberRepository.setNickname(target.id, newNickname);
        String notice = context.getString(R.string.tpl_name_changed_admin_other,
                sender.nickname, oldNickname, newNickname);
        broadcastExcept(sender.id, notice);
        reply(sender, notice);
    }

    /** Renames a member from the app UI and notifies the rest of the group. changedByLabel is typically "An Admin". */
    public void renameMemberFromApp(Member target, String newNickname, String changedByLabel) {
        String oldNickname = target.nickname;
        memberRepository.setNickname(target.id, newNickname);
        broadcastExcept(target.id, context.getString(R.string.tpl_name_changed_admin_other, changedByLabel, oldNickname, newNickname));
        SmsSendService.start(context);
    }

    private void handleAdminMessage(Member sender, String text) {
        String message = stripLeadingWord(text).trim();
        if (message.isEmpty()) {
            return;
        }
        String formatted = context.getString(R.string.tpl_admin_relay_prefix, sender.nickname, message);
        List<Member> admins = memberRepository.getActiveAdmins();
        for (Member admin : admins) {
            if (admin.id == sender.id || admin.isMuted) {
                continue;
            }
            enqueue(admin, formatted, "ADMIN");
        }
        NotificationHelper.showAdminMessage(context, formatted);
    }

    private void handleAdd(Member sender, String text) {
        if (!sender.isAdmin) {
            reply(sender, context.getString(R.string.tpl_unauthorized));
            return;
        }
        String rest = stripLeadingWord(text).trim();
        String[] parts = PhoneNumberUtils.splitNumberAndOptionalNickname(rest);
        if (parts == null) {
            reply(sender, context.getString(R.string.tpl_invalid_number));
            return;
        }
        String normalized = PhoneNumberUtils.normalize(parts[0]);
        String nickname = parts[1].trim();
        if (normalized == null) {
            reply(sender, context.getString(R.string.tpl_invalid_number));
            return;
        }
        Member existing = memberRepository.findByPhone(normalized);
        if (existing != null && existing.active) {
            reply(sender, context.getString(R.string.error_duplicate_number));
            return;
        }
        addMember(normalized, nickname, sender.nickname);
    }

    /** Adds a member and sends the standard welcome/broadcast messages. addedByLabel is either an admin's nickname or "An Admin". */
    public void addMember(String normalizedPhone, String nickname, String addedByLabel) {
        Member existing = memberRepository.findByPhone(normalizedPhone);
        String effectiveNickname = nickname == null ? "" : nickname.trim();
        if (effectiveNickname.isEmpty()) {
            effectiveNickname = existing != null && existing.nickname != null && !existing.nickname.trim().isEmpty()
                    ? existing.nickname
                    : PhoneNumberUtils.defaultNickname(normalizedPhone);
        }
        long id;
        if (existing != null && !existing.active) {
            memberRepository.reactivate(existing.id, effectiveNickname, addedByLabel);
            id = existing.id;
        } else {
            id = memberRepository.insert(normalizedPhone, effectiveNickname, false, addedByLabel);
        }
        Member newMember = memberRepository.findById(id);
        String groupName = prefs.getGroupName();
        enqueue(newMember, context.getString(R.string.tpl_added_you, addedByLabel, groupName), "SYSTEM");
        broadcastExcept(id, context.getString(R.string.tpl_added_other, addedByLabel, effectiveNickname));
        SmsSendService.start(context);
    }

    private void handleRemove(Member sender, String text) {
        if (!sender.isAdmin) {
            reply(sender, context.getString(R.string.tpl_unauthorized));
            return;
        }
        String rest = stripLeadingWord(text).trim();
        if (rest.isEmpty()) {
            return;
        }
        Member target = memberRepository.findActiveByNickname(rest);
        if (target == null) {
            String normalized = PhoneNumberUtils.normalize(rest);
            if (normalized != null) {
                Member byPhone = memberRepository.findByPhone(normalized);
                if (byPhone != null && byPhone.active) {
                    target = byPhone;
                }
            }
        }
        if (target == null) {
            return;
        }
        removeMember(target, sender.nickname);
    }

    /** Removes a member and sends the standard notice/broadcast messages. removedByLabel is either an admin's nickname or "An Admin". */
    public void removeMember(Member target, String removedByLabel) {
        memberRepository.softRemove(target.id);
        String groupName = prefs.getGroupName();
        enqueue(target, context.getString(R.string.tpl_removed_you, groupName), "SYSTEM");
        broadcastExcept(target.id, context.getString(R.string.tpl_removed_other, removedByLabel, target.nickname));
        SmsSendService.start(context);
    }

    private void handleTopicChange(Member sender, String text) {
        if (!sender.isAdmin) {
            reply(sender, context.getString(R.string.tpl_unauthorized));
            return;
        }
        String newName = stripLeadingWord(text).trim();
        if (newName.isEmpty()) {
            return;
        }
        changeGroupName(newName, sender.nickname, sender.id);
    }

    /**
     * Changes the group name and notifies members. changedByLabel is either an admin's nickname
     * or "An Admin". excludeId skips that member (the acting admin, who already knows); pass -1
     * (no matching member id) to notify every active member, e.g. when changed from the app.
     */
    public void changeGroupName(String newName, String changedByLabel, long excludeId) {
        prefs.setGroupName(newName);
        String message = context.getString(R.string.tpl_group_name_changed, changedByLabel, newName);
        broadcastExcept(excludeId, message, "SYSTEM");
        SmsSendService.start(context);
    }

    /** Sends a one-off admin direct message to a member, regardless of their mute state. */
    public void sendDirectMessage(Member target, String body) {
        String formatted = context.getString(R.string.tpl_dm_prefix, body);
        enqueue(target, formatted, "DM");
        SmsSendService.start(context);
    }

    /** Sends an admin message to every active member, regardless of mute state. */
    public void broadcastToGroup(String body) {
        String formatted = context.getString(R.string.tpl_dm_prefix, body);
        List<Member> members = memberRepository.getActiveMembers();
        for (Member m : members) {
            enqueue(m, formatted, "ADMIN");
        }
        SmsSendService.start(context);
    }

    private void relayPlainMessage(Member sender, String body) {
        String formatted = context.getString(R.string.tpl_relay_prefix, sender.nickname, body);
        broadcastExcept(sender.id, formatted, "RELAY");
    }

    private void broadcastExcept(long excludeId, String message) {
        broadcastExcept(excludeId, message, "SYSTEM");
    }

    private void broadcastExcept(long excludeId, String message, String category) {
        List<Member> recipients = memberRepository.getActiveRecipientsExcept(excludeId);
        for (Member m : recipients) {
            enqueue(m, message, category);
        }
    }

    private void reply(Member recipient, String message) {
        enqueue(recipient, message, "COMMAND");
    }

    private void enqueue(Member recipient, String message, String category) {
        long messageLogId = messageRepository.log(recipient.id, "OUT", category, message);
        outboxRepository.enqueue(recipient.id, messageLogId, recipient.phoneE164, message);
    }

    private String stripLeadingWord(String text) {
        int spaceIdx = text.indexOf(' ');
        if (spaceIdx < 0) {
            return "";
        }
        return text.substring(spaceIdx + 1);
    }
}
