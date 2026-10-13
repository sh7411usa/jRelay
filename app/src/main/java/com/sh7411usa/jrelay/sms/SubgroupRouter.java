package com.sh7411usa.jrelay.sms;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

// The poster's own sub-group is always excluded: it already got the message peer-to-peer.
public final class SubgroupRouter {
    private SubgroupRouter() {}

    public static final class OutboundPlan {
        public final List<Long> subgroupIdsToRelay;
        public final List<Long> individualMemberIds;
        public final List<String> notes;

        OutboundPlan(List<Long> subgroupIdsToRelay, List<Long> individualMemberIds, List<String> notes) {
            this.subgroupIdsToRelay = Collections.unmodifiableList(subgroupIdsToRelay);
            this.individualMemberIds = Collections.unmodifiableList(individualMemberIds);
            this.notes = Collections.unmodifiableList(notes);
        }

        @Override
        public String toString() {
            return "OutboundPlan{subgroups=" + subgroupIdsToRelay + ", individuals=" + individualMemberIds
                    + ", notes=" + notes + "}";
        }
    }

    public static OutboundPlan routePost(long posterMemberId, Long posterSubgroupId,
                                         Set<Long> activeSubgroupIds, Collection<Long> unassignedActiveMemberIds) {
        List<String> notes = new ArrayList<>();
        Set<Long> subgroups = sortedCopy(activeSubgroupIds);
        if (posterSubgroupId != null && subgroups.remove(posterSubgroupId)) {
            notes.add("excluded poster's own sub-group " + posterSubgroupId + ": already delivered peer-to-peer");
        }
        if (subgroups.isEmpty()) {
            if (activeSubgroupIds == null || activeSubgroupIds.isEmpty()) {
                notes.add("no sub-groups configured; relaying by individual SMS only");
            } else if (posterSubgroupId != null && activeSubgroupIds.size() == 1) {
                notes.add("only sub-group is the poster's own; nobody else to relay to by MMS");
            }
        }
        return new OutboundPlan(new ArrayList<>(subgroups),
                dedupeExcluding(unassignedActiveMemberIds, posterMemberId), notes);
    }

    private static Set<Long> sortedCopy(Set<Long> ids) {
        return new TreeSet<>(ids == null ? Collections.<Long>emptySet() : ids);
    }

    private static List<Long> dedupeExcluding(Collection<Long> ids, long excludeId) {
        List<Long> result = new ArrayList<>();
        if (ids == null) return result;
        Set<Long> seen = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id != null && id.longValue() != excludeId && seen.add(id)) result.add(id);
        }
        Collections.sort(result);
        return result;
    }
}
