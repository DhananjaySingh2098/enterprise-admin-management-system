package com.enterprise.admin.repository;

import java.util.Locale;

/** Builds safe, case-insensitive LIKE patterns: user input can never inject wildcards. */
public final class SearchPatterns {

    public static final char ESCAPE = '\\';

    private SearchPatterns() {
    }

    /** Returns {@code %term%} with {@code \ % _} escaped, or {@code null} for a blank term. */
    public static String contains(String term) {
        if (term == null || term.isBlank()) {
            return null;
        }
        String escaped = term.trim().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
