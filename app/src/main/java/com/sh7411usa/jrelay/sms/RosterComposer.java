package com.sh7411usa.jrelay.sms;

import com.sh7411usa.jrelay.model.Member;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

// Deliberately unwired until sub-groups exist; must be re-sent whenever a sub-group's members change.
public final class RosterComposer {
    private RosterComposer() {}

    public static String compose(String headerTemplate, String groupName, String footerText, List<Member> members) {
        String header = String.format(Locale.US, headerTemplate, groupName);
        if (members == null || members.isEmpty()) return header;
        List<Member> sorted = new ArrayList<>(members);
        sorted.sort(Comparator.comparing(m -> m.nickname == null ? "" : m.nickname.toLowerCase(Locale.US)));
        StringBuilder sb = new StringBuilder(header);
        for (Member m : sorted) {
            String nickname = m.nickname == null ? "" : m.nickname;
            String number = formatNumber(m.phoneE164);
            sb.append('\n').append(number.isEmpty() ? nickname : nickname + " " + number);
        }
        if (footerText != null && !footerText.isEmpty()) sb.append('\n').append(footerText);
        return sb.toString();
    }

    // "Strip +1" is duplicated from MessageSalt.localDigits on purpose (keeps this Android-free); change both.
    static String formatNumber(String phoneE164) {
        if (phoneE164 == null || phoneE164.isEmpty()) return "";
        String digits = phoneE164.startsWith("+") ? phoneE164.substring(1) : phoneE164;
        if (digits.length() == 11 && digits.charAt(0) == '1') digits = digits.substring(1);
        // Non-NANP: return the original WITH its "+", or it isn't dialable.
        if (digits.length() != 10) return phoneE164;
        return digits.substring(0, 3) + "-" + digits.substring(3, 6) + "-" + digits.substring(6);
    }

    public static int estimateSegments(String message, boolean ucs2) {
        if (message == null || message.isEmpty()) return 0;
        int length = message.length();
        if (length <= (ucs2 ? 70 : 160)) return 1;
        return (int) Math.ceil((double) length / (ucs2 ? 67 : 153));
    }
}
