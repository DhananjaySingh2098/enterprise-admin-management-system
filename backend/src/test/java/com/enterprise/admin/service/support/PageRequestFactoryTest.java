package com.enterprise.admin.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import com.enterprise.admin.exception.BadRequestException;
import com.enterprise.admin.service.support.PageRequestFactory.SortAllowlist;

class PageRequestFactoryTest {

    private static final SortAllowlist ALLOWLIST = new SortAllowlist("name", Sort.Direction.ASC,
            Map.of("name", List.of("lastName", "firstName"), "createdAt", List.of("createdAt")));

    @Test
    void appliesDefaultsAndStableTiebreaker() {
        Pageable pageable = PageRequestFactory.create(null, null, null, null, ALLOWLIST);
        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(20);
        assertThat(pageable.getSort()).containsExactly(Sort.Order.asc("lastName"), Sort.Order.asc("firstName"), Sort.Order.asc("id"));
    }

    @Test
    void mapsPublicSortKeysAndDirection() {
        Pageable pageable = PageRequestFactory.create(2, 50, "createdAt", "DESC", ALLOWLIST);
        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getSort()).containsExactly(Sort.Order.desc("createdAt"), Sort.Order.asc("id"));
    }

    @Test
    void rejectsPropertiesOutsideTheAllowlist() {
        for (String attempt : new String[] {"passwordHash", "roles", "lastName", "id; drop table users", "department.name"}) {
            assertThatThrownBy(() -> PageRequestFactory.create(0, 10, attempt, "asc", ALLOWLIST))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("sort must be one of createdAt, name");
        }
    }

    @Test
    void enforcesPageBoundsAndDirectionValues() {
        assertThatThrownBy(() -> PageRequestFactory.create(-1, 10, null, null, ALLOWLIST)).hasMessageContaining("page");
        assertThatThrownBy(() -> PageRequestFactory.create(0, 0, null, null, ALLOWLIST)).hasMessageContaining("size");
        assertThatThrownBy(() -> PageRequestFactory.create(0, 101, null, null, ALLOWLIST)).hasMessageContaining("between 1 and 100");
        assertThatThrownBy(() -> PageRequestFactory.create(0, 10, "name", "sideways", ALLOWLIST)).hasMessageContaining("asc or desc");
        assertThat(PageRequestFactory.create(0, 100, null, null, ALLOWLIST).getPageSize()).isEqualTo(100);
    }
}
