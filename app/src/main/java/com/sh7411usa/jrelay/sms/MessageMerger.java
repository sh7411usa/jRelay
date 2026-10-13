package com.sh7411usa.jrelay.sms;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class MessageMerger {
    public interface SegmentCounter {
        int segments(String body);
    }

    public static final class Row {
        public final long id;
        public final String body;

        public Row(long id, String body) {
            this.id = id;
            this.body = body;
        }
    }

    public static final class Merged {
        public final long keepRowId;
        public final String body;
        public final List<Long> mergedRowIds;

        public Merged(long keepRowId, String body, List<Long> mergedRowIds) {
            this.keepRowId = keepRowId;
            this.body = body;
            this.mergedRowIds = mergedRowIds;
        }
    }

    private MessageMerger() {}

    /** Rows are for ONE recipient in enqueue order; maxSegments <= 0 means unlimited. */
    public static List<Merged> merge(List<Row> rows, int maxSegments, SegmentCounter counter) {
        if (rows == null || rows.isEmpty()) return Collections.emptyList();
        List<Merged> result = new ArrayList<>();
        long keepRowId = rows.get(0).id;
        String currentBody = rows.get(0).body;
        List<Long> mergedRowIds = new ArrayList<>();
        // A group's first row is never measured, so an oversized row still becomes its own group.
        for (Row row : rows.subList(1, rows.size())) {
            String candidate = currentBody + "\n" + row.body;
            if (maxSegments <= 0 || counter.segments(candidate) <= maxSegments) {
                currentBody = candidate;
                mergedRowIds.add(row.id);
            } else {
                result.add(new Merged(keepRowId, currentBody, mergedRowIds));
                keepRowId = row.id;
                currentBody = row.body;
                mergedRowIds = new ArrayList<>();
            }
        }
        result.add(new Merged(keepRowId, currentBody, mergedRowIds));
        return result;
    }
}
