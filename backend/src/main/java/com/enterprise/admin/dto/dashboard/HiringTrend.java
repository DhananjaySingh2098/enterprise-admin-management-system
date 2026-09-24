package com.enterprise.admin.dto.dashboard;

import java.time.LocalDate;
import java.util.List;

/**
 * Hires per calendar month over a continuous range ending with the current month. Months without hires are
 * present with 0. Future hire dates are excluded.
 */
public record HiringTrend(LocalDate from, LocalDate to, long total, List<MonthlyHires> months) {

    /** @param month ISO year-month, e.g. {@code 2026-03} */
    public record MonthlyHires(String month, long hires) {
    }
}
