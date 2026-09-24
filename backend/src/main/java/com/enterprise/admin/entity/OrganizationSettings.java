package com.enterprise.admin.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** The single organization-wide settings row (id 1, seeded by V5). Optimistically locked like employees. */
@Entity
@Table(name = "organization_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrganizationSettings {

    public static final int SINGLETON_ID = 1;

    @Id
    private Integer id;

    @Column(name = "organization_name", nullable = false, length = 120)
    private String organizationName;

    @Column(name = "recent_hire_window_days", nullable = false)
    private int recentHireWindowDays;

    @Version
    private Long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by_user_id")
    private Long updatedByUserId;

    public void update(String organizationName, int recentHireWindowDays, Long actorId, Instant now) {
        this.organizationName = organizationName;
        this.recentHireWindowDays = recentHireWindowDays;
        this.updatedByUserId = actorId;
        this.updatedAt = now;
    }
}
