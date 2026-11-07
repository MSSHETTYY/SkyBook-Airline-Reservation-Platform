package com.skybook.booking.adapter.out.inventory;

import java.io.Serial;

import com.skybook.common.error.SkyBookException;

/** A business rejection (4xx) from inventory: never retried, ignored by the circuit breaker. */
public class InventoryRejectedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient SkyBookException problem;

    public InventoryRejectedException(SkyBookException problem) {
        super(problem.getMessage(), problem);
        this.problem = problem;
    }

    public SkyBookException getProblem() {
        return problem;
    }
}
