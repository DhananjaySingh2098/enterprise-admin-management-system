package com.enterprise.admin.repository;

import org.springframework.data.jpa.domain.Specification;

import com.enterprise.admin.entity.Role;
import com.enterprise.admin.entity.RoleName;
import com.enterprise.admin.entity.User;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

public final class UserSpecifications {

    private UserSpecifications() {
    }

    public static Specification<User> matches(String search, RoleName role, Boolean enabled) {
        return Specs.allOf(search(search), hasRole(role), enabled(enabled));
    }

    private static Specification<User> search(String term) {
        String pattern = SearchPatterns.contains(term);
        if (pattern == null) {
            return null;
        }
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("email")), pattern, SearchPatterns.ESCAPE),
                cb.like(cb.lower(root.get("firstName")), pattern, SearchPatterns.ESCAPE),
                cb.like(cb.lower(root.get("lastName")), pattern, SearchPatterns.ESCAPE),
                cb.like(cb.lower(cb.concat(cb.concat(root.get("firstName"), " "), root.get("lastName"))), pattern,
                        SearchPatterns.ESCAPE));
    }

    /** EXISTS subquery rather than a join, so users are never duplicated and paging stays exact. */
    private static Specification<User> hasRole(RoleName role) {
        if (role == null) {
            return null;
        }
        return (root, query, cb) -> {
            Subquery<Long> sub = query.subquery(Long.class);
            Root<User> inner = sub.from(User.class);
            Join<User, Role> roles = inner.join("roles");
            sub.select(inner.get("id")).where(cb.equal(inner.get("id"), root.get("id")), cb.equal(roles.get("name"), role));
            return cb.exists(sub);
        };
    }

    private static Specification<User> enabled(Boolean enabled) {
        return enabled == null ? null : (root, query, cb) -> cb.equal(root.get("enabled"), enabled);
    }
}
