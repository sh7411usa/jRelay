package com.sh7411usa.jrelay.util;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Pattern;

public final class MessageSalt {
    private static final Pattern PHONE_PATTERN = Pattern.compile(
            "(?:\\+\\d{1,3}[-.\\s]?)?(?:\\(\\d{3}\\)[-.\\s]?|\\d{3}[-.\\s]?)\\d{3}[-.\\s]?\\d{4}");
    private static final Pattern EXTRA_SPACES = Pattern.compile("\\s{2,}");
    private static final Random RANDOM = new Random();
    private static final char ZERO_WIDTH_SPACE = 0x200B;
    // Inverses of the salt formats below; if a format changes and these don't, the relay loops on its own echoes.
    private static final Pattern LEADING_HEX_SALT = Pattern.compile("^\\[[0-9A-F]{3}] ");
    private static final Pattern TRAILING_BRACKET_SALT = Pattern.compile(" \\[[0-9A-Fa-f:]{3,16}]$");

    private MessageSalt() {
    }

    public static String applyEnqueueTime(Prefs prefs, String senderE164, String body) {
        if (prefs.isStripPhoneNumbersEnabled()) body = stripPhoneNumbers(body);
        if (prefs.isAppendSenderNumberEnabled()) body = appendSenderNumber(senderE164, body);
        return body;
    }

    public static String applySendTime(Prefs prefs, String body) {
        if (prefs.isSaltTimestampEnabled()) body = appendTimestampSalt(body, prefs.getSaltTimestampFormat());
        if (prefs.isSaltHexEnabled()) body = applyHexSalt(body, prefs.getSaltHexPosition());
        if (prefs.isSaltZwspEnabled()) body = applyZwsp(body);
        return body;
    }

    public static String stripPhoneNumbers(String body) {
        return EXTRA_SPACES.matcher(PHONE_PATTERN.matcher(body).replaceAll("")).replaceAll(" ").trim();
    }

    public static String appendSenderNumber(String senderE164, String body) {
        return body + " " + localDigits(senderE164);
    }

    public static String localDigits(String phoneE164) {
        if (phoneE164 == null) return "";
        String digits = phoneE164.startsWith("+") ? phoneE164.substring(1) : phoneE164;
        return digits.length() == 11 && digits.charAt(0) == '1' ? digits.substring(1) : digits;
    }

    /** If you change this format, update TRAILING_BRACKET_SALT / isEchoOf and EchoNormalizeTest. */
    public static String appendTimestampSalt(String body, Prefs.SaltTimestampFormat format) {
        long now = System.currentTimeMillis();
        String ts;
        switch (format) {
            case UNIX_SECONDS: ts = String.valueOf(now / 1000L); break;
            case HEX_SECONDS: ts = Long.toHexString(now / 1000L); break;
            case HEX_MILLIS: ts = Long.toHexString(now); break;
            default: ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(now));
        }
        return body + " [" + ts + "]";
    }

    /** If you change this format, update LEADING_HEX_SALT / TRAILING_BRACKET_SALT / isEchoOf and EchoNormalizeTest. */
    public static String applyHexSalt(String body, Prefs.SaltHexPosition position) {
        String hex = String.format(Locale.US, "%03X", RANDOM.nextInt(0x1000));
        return position == Prefs.SaltHexPosition.PREPEND ? "[" + hex + "] " + body : body + " [" + hex + "]";
    }

    /** If you change this, update normalizeEchoBody / isEchoOf and EchoNormalizeTest. */
    public static String applyZwsp(String body) {
        String[] words = body.split(" ");
        if (words.length <= 1) return body;
        StringBuilder sb = new StringBuilder(words[0]);
        for (int i = 1; i < words.length; i++) {
            sb.append(' ');
            if (RANDOM.nextBoolean()) sb.append(ZERO_WIDTH_SPACE);
            sb.append(words[i]);
        }
        return sb.toString();
    }

    public static String normalizeEchoBody(String body) {
        return body == null ? "" : body.replace(String.valueOf(ZERO_WIDTH_SPACE), "").trim();
    }

    public static boolean isEchoOf(String sent, String received) {
        String s = normalizeEchoBody(sent);
        String r = normalizeEchoBody(received);
        if (s.isEmpty() || r.isEmpty()) return false;
        for (String peeled : new String[]{r, LEADING_HEX_SALT.matcher(r).replaceFirst("")}) {
            for (int peels = 0; ; peels++) {
                if (s.equals(peeled.trim())) return true;
                if (peels == 2) break;
                String next = TRAILING_BRACKET_SALT.matcher(peeled).replaceFirst("");
                if (next.equals(peeled)) break;
                peeled = next;
            }
        }
        return false;
    }
}
