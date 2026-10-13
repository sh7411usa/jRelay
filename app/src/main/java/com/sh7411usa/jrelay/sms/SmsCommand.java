package com.sh7411usa.jrelay.sms;

import com.sh7411usa.jrelay.R;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

// Declaration order is the #commands-reply order. MUTE covers #mute/#unmute; ALL covers "#all" and "all:".
public enum SmsCommand {
    COMMANDS(Group.MEMBER, R.string.cmd_line_commands),
    HELP(Group.MEMBER, R.string.cmd_line_help),
    MUTE(Group.MEMBER, R.string.cmd_line_mute),
    STOP(Group.MEMBER, R.string.cmd_line_stop),
    LIST(Group.MEMBER, R.string.cmd_line_list),
    NAME(Group.MEMBER, R.string.cmd_line_name),
    ADMIN(Group.MEMBER, R.string.cmd_line_admin),
    MODE(Group.MEMBER, R.string.cmd_line_mode),
    JOIN(Group.JOIN, R.string.cmd_line_join),
    ADD(Group.ADMIN, R.string.cmd_line_add),
    REMOVE(Group.ADMIN, R.string.cmd_line_remove),
    TOPIC(Group.ADMIN, R.string.cmd_line_topic),
    LIMITS(Group.ADMIN, R.string.cmd_line_limits),
    OVERRIDE(Group.ADMIN, R.string.cmd_line_override),
    MODE_SET(Group.ADMIN, R.string.cmd_line_mode_set),
    ALL(Group.REPLY, R.string.cmd_line_all),
    TO(Group.REPLY, R.string.cmd_line_to);

    public enum Group {
        MEMBER(R.string.command_switches_member),
        JOIN(0),
        ADMIN(R.string.command_switches_admin),
        REPLY(R.string.command_switches_reply);

        public final int headingRes;

        Group(int headingRes) {
            this.headingRes = headingRes;
        }
    }

    public final Group group;
    public final int lineRes;

    SmsCommand(Group group, int lineRes) {
        this.group = group;
        this.lineRes = lineRes;
    }

    public boolean isAlwaysOn() {
        return this == COMMANDS || this == STOP;
    }

    public boolean isSwitchable() {
        return !isAlwaysOn() && group != Group.JOIN;
    }

    // Checks and their order mirror CommandProcessor.handleCommand's dispatch.
    public static SmsCommand match(String lower) {
        if (lower.equals("#commands")) return COMMANDS;
        if (lower.equals("#help")) return HELP;
        if (lower.equals("#mute") || lower.equals("#unmute")) return MUTE;
        if (lower.equals("#stop")) return STOP;
        if (lower.equals("#list")) return LIST;
        if (lower.startsWith("#name")) return NAME;
        if (lower.startsWith("#admin")) return ADMIN;
        if (lower.startsWith("#add")) return ADD;
        if (lower.startsWith("#remove")) return REMOVE;
        if (lower.startsWith("#topic")) return TOPIC;
        // "#to" must be whole or followed by whitespace, so "#tomorrow"/"#total" are not #to.
        if (lower.equals("#to") || (lower.startsWith("#to") && Character.isWhitespace(lower.charAt(3)))) return TO;
        if (lower.equals("#limits")) return LIMITS;
        if (lower.startsWith("#override")) return OVERRIDE;
        if (lower.equals("#mode")) return MODE;
        if (lower.startsWith("#mode")) return MODE_SET;
        return null;
    }

    public static Set<SmsCommand> parseDisabled(String stored) {
        Set<SmsCommand> out = EnumSet.noneOf(SmsCommand.class);
        if (stored == null) return out;
        String padded = ',' + stored.replace(" ", "") + ',';
        for (SmsCommand c : values()) {
            if (c.isSwitchable() && padded.contains(',' + c.storedName() + ',')) out.add(c);
        }
        return out;
    }

    public static String formatDisabled(Set<SmsCommand> disabled) {
        Set<String> names = new TreeSet<>();
        for (SmsCommand c : disabled) names.add(c.storedName());
        return String.join(",", names);
    }

    public static List<SmsCommand> listLines(Set<SmsCommand> disabled, boolean isAdmin, boolean replyMode, boolean joinOpen) {
        List<SmsCommand> out = new ArrayList<>();
        for (SmsCommand c : values()) {
            boolean shown = c.group == Group.MEMBER || (c.group == Group.JOIN && joinOpen)
                    || (c.group == Group.ADMIN && isAdmin) || (c.group == Group.REPLY && replyMode);
            if (shown && !disabled.contains(c)) out.add(c);
        }
        return out;
    }

    private String storedName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
