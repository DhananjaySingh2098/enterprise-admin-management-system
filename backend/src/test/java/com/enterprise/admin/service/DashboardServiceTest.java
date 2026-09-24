package com.enterprise.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.enterprise.admin.dto.dashboard.HiringTrend;
import com.enterprise.admin.entity.EmployeeStatus;
import com.enterprise.admin.entity.RoleName;
import com.enterprise.admin.exception.BadRequestException;
import com.enterprise.admin.repository.DashboardRepository;

class DashboardServiceTest {

    private final DashboardRepository repository = mock(DashboardRepository.class);
    private final OrganizationSettingsService settings = mock(OrganizationSettingsService.class);
    private final DashboardService service = new DashboardService(repository,
            Clock.fixed(Instant.parse("2026-03-15T10:00:00Z"), ZoneOffset.UTC), settings);

    {
        given(settings.recentHireWindowDays()).willReturn(30);
    }

    @Test
    void trendIsContinuousWithExplicitZeroMonths() {
        HiringTrend trend = DashboardService.fillMonths(YearMonth.of(2025, 11), 5,
                Map.of(YearMonth.of(2025, 12), 2L, YearMonth.of(2026, 3), 1L), LocalDate.of(2025, 11, 1), LocalDate.of(2026, 3, 15));

        assertThat(trend.months()).extracting(HiringTrend.MonthlyHires::month)
                .containsExactly("2025-11", "2025-12", "2026-01", "2026-02", "2026-03");
        assertThat(trend.months()).extracting(HiringTrend.MonthlyHires::hires).containsExactly(0L, 2L, 0L, 0L, 1L);
        assertThat(trend.total()).isEqualTo(3);
    }

    @Test
    void trendRangeEndsTodayAndStartsOnTheFirstOfTheFirstMonth() {
        given(repository.countHiresByMonth(LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 15)))
                .willReturn(List.<Object[]>of(new Object[] {2026, 3, 4L}));
        HiringTrend trend = service.hiringTrend(null);
        assertThat(trend.from()).isEqualTo(LocalDate.of(2025, 4, 1));
        assertThat(trend.to()).isEqualTo(LocalDate.of(2026, 3, 15));
        assertThat(trend.months()).hasSize(12).last().extracting(HiringTrend.MonthlyHires::hires).isEqualTo(4L);
    }

    @Test
    void parametersAreBounded() {
        assertThatThrownBy(() -> service.hiringTrend(0)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.hiringTrend(25)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.recentEmployees(0)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.recentEmployees(11)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void zeroDataProducesZeroesNotNulls() {
        var summary = service.summary();
        assertThat(summary.employees().total()).isZero();
        assertThat(summary.departments().total()).isZero();
        assertThat(summary.recentHires().windowDays()).isEqualTo(30);
        assertThat(summary.recentHires().from()).isEqualTo(LocalDate.of(2026, 2, 14));
        assertThat(service.statusBreakdown()).extracting(s -> s.status() + "=" + s.count())
                .containsExactly("ACTIVE=0", "ON_LEAVE=0", "TERMINATED=0");
        var users = service.userAnalytics();
        assertThat(users.roles()).extracting(r -> r.role()).containsExactly(RoleName.ADMIN, RoleName.MANAGER, RoleName.USER);
        assertThat(users.total()).isZero();
    }

    @Test
    void summaryTotalsAreTheSumOfStatuses() {
        given(repository.countEmployeesByStatus()).willReturn(List.of(
                new Object[] {EmployeeStatus.ACTIVE, 7L}, new Object[] {EmployeeStatus.TERMINATED, 2L}));
        given(repository.countDepartmentsByActive()).willReturn(List.of(new Object[] {true, 3L}, new Object[] {false, 1L}));
        var summary = service.summary();
        assertThat(summary.employees()).isEqualTo(new com.enterprise.admin.dto.dashboard.DashboardSummary.EmployeeCounts(9, 7, 0, 2));
        assertThat(summary.departments().total()).isEqualTo(4);
        assertThat(summary.departments().inactive()).isEqualTo(1);
    }
}
