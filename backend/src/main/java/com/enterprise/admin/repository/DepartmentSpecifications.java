package com.enterprise.admin.repository;

import org.springframework.data.jpa.domain.Specification;

import com.enterprise.admin.entity.Department;

public final class DepartmentSpecifications {

    private DepartmentSpecifications() {
    }

    public static Specification<Department> matches(String search, Boolean active) {
        String pattern = SearchPatterns.contains(search);
        Specification<Department> text = pattern == null ? null : (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("name")), pattern, SearchPatterns.ESCAPE),
                cb.like(cb.lower(root.get("code")), pattern, SearchPatterns.ESCAPE));
        Specification<Department> activeSpec = active == null ? null
                : (root, query, cb) -> cb.equal(root.get("active"), active);
        return Specs.allOf(text, activeSpec);
    }
}
