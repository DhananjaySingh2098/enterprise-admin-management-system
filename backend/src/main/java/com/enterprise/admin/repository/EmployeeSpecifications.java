package com.enterprise.admin.repository;

import org.springframework.data.jpa.domain.Specification;

import com.enterprise.admin.entity.Employee;
import com.enterprise.admin.entity.EmployeeStatus;

public final class EmployeeSpecifications {

    private EmployeeSpecifications() {
    }

    public static Specification<Employee> matches(String search, Long departmentId, EmployeeStatus status) {
        return Specs.allOf(search(search), inDepartment(departmentId), hasStatus(status));
    }

    private static Specification<Employee> search(String term) {
        String pattern = SearchPatterns.contains(term);
        if (pattern == null) {
            return null;
        }
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("employeeCode")), pattern, SearchPatterns.ESCAPE),
                cb.like(cb.lower(root.get("firstName")), pattern, SearchPatterns.ESCAPE),
                cb.like(cb.lower(root.get("lastName")), pattern, SearchPatterns.ESCAPE),
                cb.like(cb.lower(cb.concat(cb.concat(root.get("firstName"), " "), root.get("lastName"))), pattern,
                        SearchPatterns.ESCAPE),
                cb.like(cb.lower(root.get("email")), pattern, SearchPatterns.ESCAPE),
                cb.like(cb.lower(root.get("jobTitle")), pattern, SearchPatterns.ESCAPE));
    }

    private static Specification<Employee> inDepartment(Long departmentId) {
        return departmentId == null ? null
                : (root, query, cb) -> cb.equal(root.get("department").get("id"), departmentId);
    }

    private static Specification<Employee> hasStatus(EmployeeStatus status) {
        return status == null ? null : (root, query, cb) -> cb.equal(root.get("status"), status);
    }
}
