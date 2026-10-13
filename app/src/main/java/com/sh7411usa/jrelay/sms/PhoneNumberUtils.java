package com.sh7411usa.jrelay.sms;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PhoneNumberUtils {
    private static final String NUMBER_ALTERNATION =
            "\\+1\\s?\\(\\d{3}\\)\\s?\\d{3}-?\\d{4}|\\+1\\d{10}|1-\\d{3}-\\d{3}-\\d{4}"
                    + "|\\(\\d{3}\\)\\s?\\d{3}-?\\d{4}|\\d{3}-\\d{3}-\\d{4}|\\d{10}|\\+\\d{8,15}";
    private static final Pattern LEADING_NUMBER_PATTERN = Pattern.compile("^(" + NUMBER_ALTERNATION + ")\\s+(.+)$");
    private static final Pattern TRAILING_NUMBER_PATTERN = Pattern.compile("^(.+?)\\s+(" + NUMBER_ALTERNATION + ")$");
    private static final String STRICT_ALLOWED_NON_DIGITS = "+()-. \t";

    // Harvests every digit anywhere in the input; to test whether a whole string IS a number use normalizeStrict.
    public static String normalize(String rawInput) {
        if (rawInput == null) return null;
        String trimmed = rawInput.trim();
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < trimmed.length(); i++) {
            // Character.digit folds non-ASCII Unicode digits to ASCII, avoiding duplicate members.
            int value = Character.digit(trimmed.charAt(i), 10);
            if (value >= 0) digits.append((char) ('0' + value));
        }
        String d = digits.toString();
        if (trimmed.startsWith("+")) return d.length() >= 11 && d.length() <= 15 ? "+" + d : null;
        if (d.length() == 11 && d.charAt(0) == '1') return "+" + d;
        return d.length() == 10 ? "+1" + d : null;
    }

    public static String mask(String phone) {
        return phone == null || phone.length() <= 4 ? "****" : "***" + phone.substring(phone.length() - 4);
    }

    public static String normalizeStrict(String rawInput) {
        if (rawInput == null) return null;
        String trimmed = rawInput.trim();
        if (trimmed.isEmpty()) return null;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (Character.digit(c, 10) < 0 && STRICT_ALLOWED_NON_DIGITS.indexOf(c) < 0) return null;
        }
        return normalize(trimmed);
    }

    public static String[] splitLeadingNumberAndRest(String text) {
        if (text == null) return null;
        Matcher m = LEADING_NUMBER_PATTERN.matcher(text.trim());
        return m.find() ? new String[]{m.group(1), m.group(2)} : null;
    }

    public static String[] splitTrailingNumberAndRest(String text) {
        if (text == null) return null;
        Matcher m = TRAILING_NUMBER_PATTERN.matcher(text.trim());
        return m.matches() ? new String[]{m.group(2), m.group(1)} : null;
    }
}
