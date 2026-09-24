package com.enterprise.admin.service.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import com.enterprise.admin.exception.BadRequestException;

/**
 * Translates client paging/sort parameters into a {@link Pageable}. Sort keys are public names mapped through an
 * explicit allowlist to entity properties, so query parameters can never reference arbitrary properties.
 */
public final class PageRequestFactory {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    private PageRequestFactory() {
    }

    /** @param fields public sort key → ordered entity properties */
    public record SortAllowlist(String defaultKey, Sort.Direction defaultDirection, Map<String, List<String>> fields) {
    }

    public static Pageable create(Integer page, Integer size, String sort, String direction, SortAllowlist allowlist) {
        int pageNumber = page == null ? 0 : page;
        int pageSize = size == null ? DEFAULT_SIZE : size;
        if (pageNumber < 0) {
            throw new BadRequestException("INVALID_PAGE", "page must be 0 or greater", "page");
        }
        if (pageSize < 1 || pageSize > MAX_SIZE) {
            throw new BadRequestException("INVALID_PAGE_SIZE", "size must be between 1 and " + MAX_SIZE, "size");
        }

        String key = sort == null || sort.isBlank() ? allowlist.defaultKey() : sort.trim();
        List<String> properties = allowlist.fields().get(key);
        if (properties == null) {
            throw new BadRequestException("INVALID_SORT",
                    "sort must be one of " + String.join(", ", allowlist.fields().keySet().stream().sorted().toList()), "sort");
        }
        Sort.Direction dir = parseDirection(direction, allowlist.defaultDirection());

        List<Sort.Order> orders = new ArrayList<>();
        properties.forEach(property -> orders.add(new Sort.Order(dir, property)));
        orders.add(Sort.Order.asc("id")); // deterministic order across pages
        return PageRequest.of(pageNumber, pageSize, Sort.by(orders));
    }

    private static Sort.Direction parseDirection(String direction, Sort.Direction fallback) {
        if (direction == null || direction.isBlank()) {
            return fallback;
        }
        return switch (direction.trim().toLowerCase(Locale.ROOT)) {
            case "asc" -> Sort.Direction.ASC;
            case "desc" -> Sort.Direction.DESC;
            default -> throw new BadRequestException("INVALID_SORT_DIRECTION", "direction must be asc or desc", "direction");
        };
    }
}
