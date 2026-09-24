package com.enterprise.admin.dto.notification;

import java.time.Instant;

import com.enterprise.admin.entity.AuditEntityType;
import com.enterprise.admin.entity.Notification;
import com.enterprise.admin.entity.NotificationType;

public record NotificationResponse(Long id, NotificationType type, String title, String message,
                                   AuditEntityType relatedEntityType, String relatedEntityId, boolean read,
                                   Instant readAt, Instant createdAt) {

    public static NotificationResponse from(Notification n) {
        return new NotificationResponse(n.getId(), n.getType(), n.getTitle(), n.getMessage(), n.getRelatedEntityType(),
                n.getRelatedEntityId(), n.isRead(), n.getReadAt(), n.getCreatedAt());
    }
}
