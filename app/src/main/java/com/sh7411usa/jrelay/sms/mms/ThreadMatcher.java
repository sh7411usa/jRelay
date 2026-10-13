package com.sh7411usa.jrelay.sms.mms;

import com.sh7411usa.jrelay.sms.PhoneNumberUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

/**
 * Matches an inbound group MMS to a sub-group by trying "participants minus exactly one" (the
 * relay's own, unknown number) against recorded signatures. The full participant set is
 * deliberately never a candidate: it would bridge a sub-group + outsider private chat.
 */
public final class ThreadMatcher {
    private ThreadMatcher() {
    }

    public static String signature(Collection<String> numbers) {
        return String.join(",", normalizeInto(new TreeSet<>(), numbers));
    }

    private static TreeSet<String> normalizeInto(TreeSet<String> set, Collection<String> numbers) {
        if (numbers != null) {
            for (String raw : numbers) {
                String n = PhoneNumberUtils.normalize(raw);
                if (n != null) set.add(n);
            }
        }
        return set;
    }

    public interface SignatureLookup {
        Long subgroupFor(String signature);
    }

    public static final class Match {
        public final long subgroupId;
        public final String signature;
        public final String removedParticipant;

        public Match(long subgroupId, String signature, String removedParticipant) {
            this.subgroupId = subgroupId;
            this.signature = signature;
            this.removedParticipant = removedParticipant;
        }
    }

    /** Returns null on no hit or when hits disagree on the sub-group. */
    public static Match match(String sender, Collection<String> recipients, SignatureLookup lookup) {
        String normalizedSender = sender == null ? null : PhoneNumberUtils.normalize(sender);
        if (normalizedSender == null || lookup == null) return null;
        TreeSet<String> participants = normalizeInto(new TreeSet<>(), recipients);
        participants.add(normalizedSender);
        Match found = null;
        for (String toRemove : participants) {
            if (toRemove.equals(normalizedSender)) continue; // sender must stay in the thread
            List<String> candidateSet = new ArrayList<>(participants);
            candidateSet.remove(toRemove);
            String candidateSignature = signature(candidateSet);
            Long subgroupId = lookup.subgroupFor(candidateSignature);
            if (subgroupId == null) continue;
            if (found == null) found = new Match(subgroupId, candidateSignature, toRemove);
            else if (found.subgroupId != subgroupId) return null;
        }
        return found;
    }
}
