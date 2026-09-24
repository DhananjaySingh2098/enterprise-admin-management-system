package com.enterprise.admin.controller;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.enterprise.admin.dto.dashboard.DashboardSummary;
import com.enterprise.admin.dto.dashboard.DepartmentHeadcount;
import com.enterprise.admin.dto.dashboard.HiringTrend;
import com.enterprise.admin.dto.dashboard.RecentEmployee;
import com.enterprise.admin.dto.dashboard.StatusCount;
import com.enterprise.admin.dto.dashboard.UserAnalytics;
import com.enterprise.admin.service.DashboardService;

import lombok.RequiredArgsConstructor;

/**
 * Read-only management analytics. Workforce endpoints: ADMIN and MANAGER. System-user analytics: ADMIN only.
 * USER receives 403 — the personal workspace needs no organisation-wide aggregates.
 */
@RestController
@RequestMapping("/api/dashboard")
@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/summary")
    public DashboardSummary summary() {
        return dashboardService.summary();
    }

    @GetMapping("/headcount-by-department")
    public List<DepartmentHeadcount> headcountByDepartment() {
        return dashboardService.headcountByDepartment();
    }

    @GetMapping("/status-breakdown")
    public List<StatusCount> statusBreakdown() {
        return dashboardService.statusBreakdown();
    }

    @GetMapping("/hiring-trend")
    public HiringTrend hiringTrend(@RequestParam(required = false) Integer months) {
        return dashboardService.hiringTrend(months);
    }

    @GetMapping("/recent-employees")
    public List<RecentEmployee> recentEmployees(@RequestParam(required = false) Integer limit) {
        return dashboardService.recentEmployees(limit);
    }

    @GetMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    public UserAnalytics users() {
        return dashboardService.userAnalytics();
    }
}
