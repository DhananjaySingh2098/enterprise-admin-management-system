package com.enterprise.admin.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.enterprise.admin.dto.dashboard.DashboardSummary;
import com.enterprise.admin.dto.dashboard.DashboardSummary.DepartmentCounts;
import com.enterprise.admin.dto.dashboard.DashboardSummary.EmployeeCounts;
import com.enterprise.admin.dto.dashboard.DashboardSummary.RecentHires;
import com.enterprise.admin.dto.dashboard.DepartmentHeadcount;
import com.enterprise.admin.dto.dashboard.HiringTrend;
import com.enterprise.admin.dto.dashboard.RecentEmployee;
import com.enterprise.admin.dto.dashboard.StatusCount;
import com.enterprise.admin.dto.dashboard.UserAnalytics;
import com.enterprise.admin.entity.EmployeeStatus;
import com.enterprise.admin.entity.RoleName;
import com.enterprise.admin.exception.BadRequestException;
import com.enterprise.admin.repository.DashboardRepository;

import lombok.RequiredArgsConstructor;

/**
 * Dashboard metrics. "Today" is the current UTC date from the application clock; hire dates are calendar dates.
 * See docs/ARCHITECTURE.md ("Dashboard analytics") for every definition.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardService {

    public static final int DEFAULT_TREND_MONTHS = 12;
    public static final int MAX_TREND_MONTHS = 24;
    public static final int DEFAULT_RECENT_LIMIT = 5;
    public static final int MAX_RECENT_LIMIT = 10;

    private final DashboardRepository repository;
    private final Clock clock;
    /** Source of the configurable recent-hire window (organization settings, 30 days by default). */
    private final OrganizationSettingsService settings;

    public DashboardSummary summary() {
        Map<EmployeeStatus, Long> byStatus = statusCounts();
        long active = byStatus.get(EmployeeStatus.ACTIVE);
        long onLeave = byStatus.get(EmployeeStatus.ON_LEAVE);
        long terminated = byStatus.get(EmployeeStatus.TERMINATED);

        long activeDepartments = 0;
        long inactiveDepartments = 0;
        for (Object[] row : repository.countDepartmentsByActive()) {
            if ((Boolean) row[0]) {
                activeDepartments = (Long) row[1];
            } else {
                inactiveDepartments = (Long) row[1];
            }
        }

        LocalDate today = today();
        int windowDays = settings.recentHireWindowDays();
        LocalDate from = today.minusDays(windowDays - 1L);
        long recent = repository.countHiredBetween(from, today);

        return new DashboardSummary(
                new EmployeeCounts(active + onLeave + terminated, active, onLeave, terminated),
                new DepartmentCounts(activeDepartments + inactiveDepartments, activeDepartments, inactiveDepartments),
                new RecentHires(recent, windowDays, from, today),
                clock.instant());
    }

    /** All three statuses, always, in a fixed order (zero when absent). */
    public List<StatusCount> statusBreakdown() {
        Map<EmployeeStatus, Long> counts = statusCounts();
        return List.of(EmployeeStatus.values()).stream().map(s -> new StatusCount(s, counts.get(s))).toList();
    }

    public List<DepartmentHeadcount> headcountByDepartment() {
        return repository.headcountByDepartment();
    }

    public HiringTrend hiringTrend(Integer months) {
        int count = months == null ? DEFAULT_TREND_MONTHS : months;
        if (count < 1 || count > MAX_TREND_MONTHS) {
            throw new BadRequestException("INVALID_RANGE", "months must be between 1 and " + MAX_TREND_MONTHS, "months");
        }
        LocalDate today = today();
        YearMonth last = YearMonth.from(today);
        YearMonth first = last.minusMonths(count - 1L);
        LocalDate from = first.atDay(1);

        Map<YearMonth, Long> hires = new HashMap<>();
        for (Object[] row : repository.countHiresByMonth(from, today)) {
            hires.put(YearMonth.of(((Number) row[0]).intValue(), ((Number) row[1]).intValue()), (Long) row[2]);
        }
        return fillMonths(first, count, hires, from, today);
    }

    /** Continuous month series; missing months are explicit zeros. */
    static HiringTrend fillMonths(YearMonth first, int count, Map<YearMonth, Long> hires, LocalDate from, LocalDate to) {
        List<HiringTrend.MonthlyHires> series = new ArrayList<>(count);
        long total = 0;
        for (int i = 0; i < count; i++) {
            YearMonth month = first.plusMonths(i);
            long value = hires.getOrDefault(month, 0L);
            total += value;
            series.add(new HiringTrend.MonthlyHires(month.toString(), value));
        }
        return new HiringTrend(from, to, total, List.copyOf(series));
    }

    public List<RecentEmployee> recentEmployees(Integer limit) {
        int size = limit == null ? DEFAULT_RECENT_LIMIT : limit;
        if (size < 1 || size > MAX_RECENT_LIMIT) {
            throw new BadRequestException("INVALID_LIMIT", "limit must be between 1 and " + MAX_RECENT_LIMIT, "limit");
        }
        return repository.findRecentHires(today(), PageRequest.of(0, size)).stream().map(RecentEmployee::from).toList();
    }

    public UserAnalytics userAnalytics() {
        long enabled = 0;
        long disabled = 0;
        for (Object[] row : repository.countUsersByEnabled()) {
            if ((Boolean) row[0]) {
                enabled = (Long) row[1];
            } else {
                disabled = (Long) row[1];
            }
        }
        Map<RoleName, UserAnalytics.RoleCount> byRole = new EnumMap<>(RoleName.class);
        for (RoleName role : RoleName.values()) {
            byRole.put(role, new UserAnalytics.RoleCount(role, 0, 0));
        }
        for (Object[] row : repository.countUsersByRole()) {
            RoleName role = (RoleName) row[0];
            byRole.put(role, new UserAnalytics.RoleCount(role, ((Number) row[1]).longValue(), ((Number) row[2]).longValue()));
        }
        return new UserAnalytics(enabled + disabled, enabled, disabled, repository.countMultiRoleUsers(),
                List.copyOf(byRole.values()));
    }

    private Map<EmployeeStatus, Long> statusCounts() {
        Map<EmployeeStatus, Long> counts = new EnumMap<>(EmployeeStatus.class);
        for (EmployeeStatus status : EmployeeStatus.values()) {
            counts.put(status, 0L);
        }
        for (Object[] row : repository.countEmployeesByStatus()) {
            counts.put((EmployeeStatus) row[0], (Long) row[1]);
        }
        return counts;
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
