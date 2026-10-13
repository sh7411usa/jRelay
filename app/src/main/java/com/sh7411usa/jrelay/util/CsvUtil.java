package com.sh7411usa.jrelay.util;

import java.util.ArrayList;
import java.util.List;

public class CsvUtil {
    public static String escapeField(String field) {
        if (field == null) return "";
        if (field.contains(",") || field.contains("\"") || field.contains("\n")) return "\"" + field.replace("\"", "\"\"") + "\"";
        return field;
    }

    public static List<String> parseLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes && c == '"') {
                if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else inQuotes = false;
            } else if (!inQuotes && c == '"') inQuotes = true;
            else if (!inQuotes && c == ',') {
                fields.add(current.toString());
                current.setLength(0);
            } else current.append(c);
        }
        fields.add(current.toString());
        return fields;
    }
}
