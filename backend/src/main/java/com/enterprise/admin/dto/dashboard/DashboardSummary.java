package com.enterprise.admin.dto.dashboard;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Organisation-wide totals (ADMIN, MANAGER). All values are live counts; nothing is estimated or projected.
 *
 * @param recentHires employees whose hire date falls in the last {@code windowDays} days, up to and including today
 */
public record DashboardSummary(EmployeeCounts employees, DepartmentCounts departments, RecentHires recentHires,
                               Instant generatedAt) {

    /** {@code total = active + onLeave + terminated}. */
    public record EmployeeCounts(long total, long active, long onLeave, long terminated) {
    }

    public record DepartmentCounts(long total, long active, long inactive) {
    }

    public record RecentHires(long count, int windowDays, LocalDate from, LocalDate to) {
    }
}
