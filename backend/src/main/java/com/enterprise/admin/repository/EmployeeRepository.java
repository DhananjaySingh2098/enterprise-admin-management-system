package com.enterprise.admin.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.enterprise.admin.entity.Employee;

public interface EmployeeRepository extends JpaRepository<Employee, Long>, JpaSpecificationExecutor<Employee> {

    /** Page of employees with their department fetched in the same query (to-one join; safe with pagination). */
    @Override
    @EntityGraph(attributePaths = "department")
    Page<Employee> findAll(Specification<Employee> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"department", "user"})
    Optional<Employee> findWithDetailsById(Long id);

    boolean existsByEmployeeCodeAndIdNot(String employeeCode, Long id);

    boolean existsByEmployeeCode(String employeeCode);

    boolean existsByEmailAndIdNot(String email, Long id);

    boolean existsByEmail(String email);

    boolean existsByUserIdAndIdNot(Long userId, Long id);

    boolean existsByUserId(Long userId);

    /** Current headcount (ACTIVE + ON_LEAVE) per department, for the given departments only. */
    @Query("""
            select e.department.id, count(e) from Employee e
            where e.department.id in :departmentIds
              and e.status <> com.enterprise.admin.entity.EmployeeStatus.TERMINATED
            group by e.department.id""")
    List<Object[]> countCurrentByDepartment(@Param("departmentIds") Collection<Long> departmentIds);
}
