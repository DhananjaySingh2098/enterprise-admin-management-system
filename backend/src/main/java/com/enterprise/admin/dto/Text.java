package com.enterprise.admin.dto;

/** Input normalisation shared by request DTOs. */
public final class Text {

    private Text() {
    }

    public static String trim(String value) {
        return value == null ? null : value.trim();
    }

    public static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
