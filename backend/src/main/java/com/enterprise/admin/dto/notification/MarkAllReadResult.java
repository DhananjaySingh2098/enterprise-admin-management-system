package com.enterprise.admin.dto.notification;

/** How many notifications changed from unread to read, and what remains unread (always 0 afterwards). */
public record MarkAllReadResult(int updated, long unread) {
}
