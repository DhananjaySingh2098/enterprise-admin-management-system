package com.enterprise.admin.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.enterprise.admin.dto.PageResponse;
import com.enterprise.admin.dto.notification.MarkAllReadResult;
import com.enterprise.admin.dto.notification.NotificationResponse;
import com.enterprise.admin.dto.notification.UnreadCount;
import com.enterprise.admin.exception.BadRequestException;
import com.enterprise.admin.security.AuthenticatedUser;
import com.enterprise.admin.service.NotificationService;

import lombok.RequiredArgsConstructor;

/** The caller's own notifications (any role). The owner is always the authenticated principal, never a parameter. */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    /** {@code status}: {@code all} (default) or {@code unread}. Newest first. */
    @GetMapping
    public PageResponse<NotificationResponse> list(@AuthenticationPrincipal AuthenticatedUser principal,
                                                   @RequestParam(defaultValue = "all") String status,
                                                   @RequestParam(required = false) Integer page,
                                                   @RequestParam(required = false) Integer size) {
        boolean unreadOnly = switch (status) {
            case "all" -> false;
            case "unread" -> true;
            default -> throw new BadRequestException("INVALID_STATUS", "status must be all or unread", "status");
        };
        return notificationService.list(principal.id(), unreadOnly, page, size);
    }

    @GetMapping("/unread-count")
    public UnreadCount unreadCount(@AuthenticationPrincipal AuthenticatedUser principal) {
        return notificationService.unreadCount(principal.id());
    }

    @PutMapping("/{id}/read")
    public NotificationResponse markRead(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable Long id) {
        return notificationService.markRead(principal.id(), id);
    }

    @PutMapping("/read-all")
    public MarkAllReadResult markAllRead(@AuthenticationPrincipal AuthenticatedUser principal) {
        return notificationService.markAllRead(principal.id());
    }
}
