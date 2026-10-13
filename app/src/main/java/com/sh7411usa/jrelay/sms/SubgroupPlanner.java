package com.sh7411usa.jrelay.sms;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Proposes sub-group placements only; never moves an already-assigned member. */
public final class SubgroupPlanner {
    public static final int MIN_NEW_SUBGROUP_SIZE = 3;
    public static final int MERGE_THRESHOLD = 4;

    private SubgroupPlanner() {
    }

    public static Plan planFor(List<Long> unassignedMemberIds, Map<Integer, Integer> currentSizes,
                                int targetSize) {
        List<String> notes = new ArrayList<>();
        List<Assignment> assignments = new ArrayList<>();
        if (unassignedMemberIds == null || unassignedMemberIds.isEmpty()) {
            notes.add("No unassigned members; nothing to plan.");
            return new Plan(assignments, notes);
        }
        if (targetSize <= 1) {
            notes.add("Target size " + targetSize + " is too small to form a real sub-group "
                    + "(need at least " + MIN_NEW_SUBGROUP_SIZE + "); no placements proposed. "
                    + "Choose a target size before planning.");
            return new Plan(assignments, notes);
        }
        TreeMap<Integer, Integer> sizes = new TreeMap<>();
        if (currentSizes != null) sizes.putAll(currentSizes);
        List<Long> ids = unassignedMemberIds;
        int next = 0, n = ids.size();
        for (Map.Entry<Integer, Integer> e : sizes.entrySet()) {
            int size = e.getValue();
            if (size > targetSize) {
                notes.add("Sub-group " + e.getKey() + " is already over target ("
                        + size + "/" + targetSize + "); left as-is. Consider an explicit "
                        + "admin rebalance if this should shrink.");
            }
            for (int open = targetSize - size; open > 0 && next < n; open--) {
                assignments.add(new Assignment(ids.get(next++), e.getKey(), false));
            }
        }
        // Leftovers below MIN_NEW_SUBGROUP_SIZE stay unassigned: never overfill a thread past target.
        for (int groupId = sizes.isEmpty() ? 1 : sizes.lastKey() + 1; next < n; groupId++) {
            if (Math.min(targetSize, n - next) < MIN_NEW_SUBGROUP_SIZE) {
                notes.add((n - next) + " member(s) left unassigned: not enough people to "
                        + "form a sub-group of at least " + MIN_NEW_SUBGROUP_SIZE + " and no "
                        + "existing under-full sub-group left to add them to. They will receive "
                        + "individual SMS instead of a group thread.");
                break;
            }
            for (int end = Math.min(n, next + targetSize); next < end; ) {
                assignments.add(new Assignment(ids.get(next++), groupId, true));
            }
        }
        return new Plan(assignments, notes);
    }

    public static Plan planMerges(Map<Integer, List<Long>> subgroupMembers, int targetSize) {
        List<String> notes = new ArrayList<>();
        List<Assignment> assignments = new ArrayList<>();
        TreeMap<Integer, List<Long>> groups = new TreeMap<>();
        if (subgroupMembers != null) {
            for (Map.Entry<Integer, List<Long>> e : subgroupMembers.entrySet()) {
                if (e.getValue() != null && !e.getValue().isEmpty()) {
                    groups.put(e.getKey(), new ArrayList<>(e.getValue()));
                }
            }
        }
        if (groups.size() < 2) {
            notes.add("Fewer than two sub-groups exist; nothing to merge.");
            return new Plan(assignments, notes);
        }
        List<Integer> sources = new ArrayList<>();
        for (Map.Entry<Integer, List<Long>> e : groups.entrySet()) {
            if (e.getValue().size() <= MERGE_THRESHOLD) sources.add(e.getKey());
        }
        if (sources.isEmpty()) {
            notes.add("No sub-group is at or below " + MERGE_THRESHOLD + " members; nothing to merge.");
            return new Plan(assignments, notes);
        }
        sources.sort((a, b) -> {
            int bySize = Integer.compare(groups.get(a).size(), groups.get(b).size());
            return bySize != 0 ? bySize : Integer.compare(a, b);
        });
        Set<Integer> dissolved = new HashSet<>();
        for (Integer sourceId : sources) {
            if (dissolved.contains(sourceId)) continue;
            List<Long> movers = groups.get(sourceId);
            Integer destId = null;
            for (Map.Entry<Integer, List<Long>> c : groups.entrySet()) {
                if (!c.getKey().equals(sourceId) && !dissolved.contains(c.getKey())
                        && c.getValue().size() + movers.size() <= targetSize) {
                    destId = c.getKey();
                    break;
                }
            }
            if (destId == null) {
                notes.add("Sub-group " + sourceId + " has only " + movers.size()
                        + " member(s), but no other sub-group has room for all of them without "
                        + "exceeding " + targetSize + "; left as-is.");
                continue;
            }
            for (Long memberId : movers) assignments.add(new Assignment(memberId, destId, false));
            List<Long> dest = groups.get(destId);
            dest.addAll(movers);
            dissolved.add(sourceId);
            notes.add("Sub-group " + sourceId + " (" + movers.size()
                    + " member(s)) folds into sub-group " + destId + ", which becomes "
                    + dest.size() + "/" + targetSize + ".");
        }
        return new Plan(assignments, notes);
    }

    public static String summarize(Plan plan) {
        if (plan.assignments.isEmpty() && plan.advisoryNotes.isEmpty()) return "No changes proposed.";
        TreeMap<Integer, int[]> byGroup = new TreeMap<>(); // id -> [count, isNew]
        for (Assignment a : plan.assignments) {
            byGroup.computeIfAbsent(a.subgroupId, k -> new int[]{0, a.isNewSubgroup ? 1 : 0})[0]++;
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<Integer, int[]> e : byGroup.entrySet()) {
            int count = e.getValue()[0];
            parts.add(count + (count == 1 ? " member" : " members") + " into "
                    + (e.getValue()[1] == 1 ? "a new group " : "group ") + e.getKey());
        }
        StringBuilder sb = new StringBuilder();
        if (!parts.isEmpty()) sb.append(String.join(", ", parts)).append(".");
        for (String note : plan.advisoryNotes) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(note);
        }
        return sb.toString();
    }

    public static final class Assignment {
        public final long memberId;
        public final int subgroupId;
        public final boolean isNewSubgroup;

        Assignment(long memberId, int subgroupId, boolean isNewSubgroup) {
            this.memberId = memberId;
            this.subgroupId = subgroupId;
            this.isNewSubgroup = isNewSubgroup;
        }
    }

    /** advisoryNotes are never assignments and must never be auto-applied. */
    public static final class Plan {
        public final List<Assignment> assignments;
        public final List<String> advisoryNotes;

        Plan(List<Assignment> assignments, List<String> advisoryNotes) {
            this.assignments = Collections.unmodifiableList(assignments);
            this.advisoryNotes = Collections.unmodifiableList(advisoryNotes);
        }
    }
}
