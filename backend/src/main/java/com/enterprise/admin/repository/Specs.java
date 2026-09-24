package com.enterprise.admin.repository;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.springframework.data.jpa.domain.Specification;

/** Combines optional filters: {@code null} means "no filter" (Spring Data 4 rejects null operands). */
final class Specs {

    private Specs() {
    }

    @SafeVarargs
    static <T> Specification<T> allOf(Specification<T>... specs) {
        List<Specification<T>> present = Arrays.stream(specs).filter(Objects::nonNull).toList();
        return present.isEmpty() ? Specification.unrestricted() : Specification.allOf(present);
    }
}
