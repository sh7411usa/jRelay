package com.sh7411usa.jrelay.sms;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MessageIntent {
    private static final Pattern POST_PREFIX_PATTERN = Pattern.compile("(?i)^(?:#all\\s+|all:)(.*)$", Pattern.DOTALL);
    private static final Pattern MULTILINE_WHITESPACE_RUN = Pattern.compile("\\s+");
    private static final long MILLIS_PER_HOUR = 3_600_000L;
    private static final int MAX_NICKNAME_LENGTH = 32;

    private MessageIntent() {
    }

    // Anti-forgery: a relayed body must stay one line so it cannot plant a fake "[Admin]: ..." line.
    public static String sanitizeRelayBody(String body) {
        if (body == null) return "";
        return MULTILINE_WHITESPACE_RUN.matcher(body.trim()).replaceAll(" ").trim();
    }

    // Leading '[', '@', '#' are banned because the app's own system prefixes start with them.
    public static boolean isValidNickname(String nickname) {
        if (nickname == null) return false;
        String trimmed = nickname.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_NICKNAME_LENGTH) return false;
        for (int i = 0; i < nickname.length(); i++) {
            char c = nickname.charAt(i);
            if (c == ':' || Character.isISOControl(c)) return false;
        }
        return !isSystemPrefixChar(trimmed.charAt(0));
    }

    public static String sanitizeNicknameForRender(String nickname) {
        String collapsed = sanitizeRelayBody(nickname);
        int start = 0;
        while (start < collapsed.length() && isSystemPrefixChar(collapsed.charAt(start))) start++;
        String stripped = collapsed.substring(start).trim();
        return stripped.isEmpty() ? "Member" : stripped;
    }

    public static String stripPostPrefix(String trimmed) {
        if (trimmed == null) return null;
        Matcher matcher = POST_PREFIX_PATTERN.matcher(trimmed);
        if (!matcher.matches()) return null;
        String body = matcher.group(1).trim();
        return body.isEmpty() ? null : body;
    }

    public static String canonicalBareKeyword(String trimmed) {
        if (trimmed == null) return null;
        switch (trimmed.trim().toUpperCase(Locale.ROOT)) {
            case "STOP": case "UNSUBSCRIBE": case "CANCEL": case "QUIT": case "END": return "#stop";
            case "HELP": return "#commands";
            case "MUTE": return "#mute";
            case "UNMUTE": return "#unmute";
            default: return null;
        }
    }

    public static boolean isWithinReplyWindow(long postTimestamp, long now, int windowHours) {
        return windowHours <= 0 || now - postTimestamp <= windowHours * MILLIS_PER_HOUR;
    }

    private static boolean isSystemPrefixChar(char c) {
        return c == '[' || c == '@' || c == '#';
    }
}
