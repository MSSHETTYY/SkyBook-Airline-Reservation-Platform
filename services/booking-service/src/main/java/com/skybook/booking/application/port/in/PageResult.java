package com.skybook.booking.application.port.in;

import java.util.List;
import java.util.function.Function;

/** A page of results; the application core's own type so it does not depend on Spring Data. */
public record PageResult<T>(List<T> items, int page, int size, long totalElements) {

    public PageResult {
        items = List.copyOf(items);
    }

    public <R> PageResult<R> map(Function<T, R> mapper) {
        return new PageResult<>(items.stream().map(mapper).toList(), page, size, totalElements);
    }
}
