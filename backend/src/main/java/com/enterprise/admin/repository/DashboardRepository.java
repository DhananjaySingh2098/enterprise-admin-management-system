package com.enterprise.admin.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.enterprise.admin.dto.dashboard.DepartmentHeadcount;
import com.enterprise.admin.entity.Employee;

/**
 * Read-only aggregate queries for the dashboard. Every metric is computed by the database (GROUP BY / COUNT);
 * no query loads whole tables into memory.
 */
public interface DashboardRepository extends Repository<Employee, Long> {

    /** One row per status that has employees: [EmployeeStatus, count]. */
    @Query("select e.status, count(e) from Employee e group by e.status")
    List<Object[]> countEmployeesByStatus();

    /** [Boolean active, count] per department activity state. */
    @Query("select d.active, count(d) from Department d group by d.active")
    List<Object[]> countDepartmentsByActive();

    @Query("select count(e) from Employee e where e.hireDate between :from and :to")
    long countHiredBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * Every department with its current headcount (ACTIVE + ON_LEAVE). LEFT JOIN keeps departments with no
     * employees (0). Largest first, then by name.
     */
    @Query("""
            select new com.enterprise.admin.dto.dashboard.DepartmentHeadcount(d.id, d.name, d.code, d.active, count(e.id))
            from Department d
            left join Employee e on e.department = d and e.status <> com.enterprise.admin.entity.EmployeeStatus.TERMINATED
            group by d.id, d.name, d.code, d.active
            order by count(e.id) desc, d.name asc""")
    List<DepartmentHeadcount> headcountByDepartment();

    /** [year, month, count] of hires within the range (inclusive). */
    @Query("""
            select extract(year from e.hireDate), extract(month from e.hireDate), count(e)
            from Employee e
            where e.hireDate between :from and :to
            group by extract(year from e.hireDate), extract(month from e.hireDate)""")
    List<Object[]> countHiresByMonth(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Most recent hires up to {@code today}; department fetched in the same query. */
    @EntityGraph(attributePaths = "department")
    @Query("select e from Employee e where e.hireDate <= :today order by e.hireDate desc, e.id desc")
    List<Employee> findRecentHires(@Param("today") LocalDate today, Pageable limit);

    /** [Boolean enabled, count] of system accounts. */
    @Query("select u.enabled, count(u) from User u group by u.enabled")
    List<Object[]> countUsersByEnabled();

    /** [RoleName, distinct holders, enabled holders] per role. */
    @Query("""
            select r.name, count(distinct u.id), sum(case when u.enabled = true then 1 else 0 end)
            from User u join u.roles r
            group by r.name""")
    List<Object[]> countUsersByRole();

    @Query("select count(u) from User u where size(u.roles) > 1")
    long countMultiRoleUsers();
}
