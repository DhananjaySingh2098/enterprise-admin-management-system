package com.enterprise.admin.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import tools.jackson.databind.json.JsonMapper;

/**
 * Small, ordered bag of facts attached to an audit event, and the redaction applied before it is stored.
 *
 * <p>Callers only ever pass non-secret facts (changed field <em>names</em>, from/to statuses, reasons, counts).
 * Redaction is a second, independent line of defence:
 * <ul>
 *   <li>keys that look secret ({@code password}, {@code token}, {@code secret}, {@code hash}, {@code cookie},
 *       {@code authorization}, {@code credential}, {@code jwt}, {@code bearer}, {@code apikey}) are dropped;</li>
 *   <li>values that look like a JWT or a bearer/basic credential are replaced by {@code [REDACTED]};</li>
 *   <li>control characters are stripped, strings are capped at 200 characters, collections at 20 items, and the
 *       whole JSON at 2000 characters.</li>
 * </ul>
 */
public final class AuditDetails {

    static final String REDACTED = "[REDACTED]";
    private static final Pattern SECRET_KEY = Pattern.compile(
            "(?i).*(pass(word|wd)?|secret|token|jwt|authori[sz]ation|cookie|credential|hash|bearer|api[-_]?key).*");
    private static final Pattern SECRET_VALUE = Pattern.compile(
            "(?i)(^|.*\\s)(bearer|basic)\\s+\\S+.*|.*eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]*.*");
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");
    private static final int MAX_STRING = 200;
    private static final int MAX_ITEMS = 20;
    static final int MAX_JSON = 2000;

    private final Map<String, Object> values = new LinkedHashMap<>();

    private AuditDetails() {
    }

    public static AuditDetails none() {
        return new AuditDetails();
    }

    public static AuditDetails of(String key, Object value) {
        return new AuditDetails().and(key, value);
    }

    /** Adds a fact; {@code null} values and empty collections are skipped. */
    public AuditDetails and(String key, Object value) {
        if (value != null && !(value instanceof Collection<?> c && c.isEmpty())) {
            values.put(key, value);
        }
        return this;
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    /** Redacted JSON, or {@code null} when there is nothing to store. */
    String toJson(JsonMapper mapper) {
        Map<String, Object> safe = redactMap(values);
        if (safe.isEmpty()) {
            return null;
        }
        String json = mapper.writeValueAsString(safe);
        return json.length() <= MAX_JSON ? json : mapper.writeValueAsString(Map.of("note", "details truncated"));
    }

    static Map<String, Object> redactMap(Map<?, ?> input) {
        Map<String, Object> out = new LinkedHashMap<>();
        input.forEach((key, value) -> {
            String name = clean(String.valueOf(key));
            if (!SECRET_KEY.matcher(name).matches()) {
                Object safe = redactValue(value);
                if (safe != null) {
                    out.put(name, safe);
                }
            }
        });
        return out;
    }

    private static Object redactValue(Object value) {
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Enum<?> e) {
            return e.name();
        }
        if (value instanceof Map<?, ?> map) {
            return redactMap(map);
        }
        if (value instanceof Collection<?> collection) {
            List<Object> out = new ArrayList<>();
            for (Object item : collection) {
                if (out.size() == MAX_ITEMS) {
                    break;
                }
                out.add(redactValue(item));
            }
            return out;
        }
        String text = clean(String.valueOf(value));
        return SECRET_VALUE.matcher(text).matches() ? REDACTED : text;
    }

    private static String clean(String text) {
        String stripped = CONTROL.matcher(text).replaceAll("");
        return stripped.length() <= MAX_STRING ? stripped : stripped.substring(0, MAX_STRING) + "…";
    }
}
