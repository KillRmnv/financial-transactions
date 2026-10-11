package com.idfinance.financial_transactions.dto;

import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Which way to move a tracked currency's sweep.
 *
 * <p>
 * An explicit {@code active} rather than two endpoints, because "activate this"
 * and "deactivate this" are the same request with a different value: one
 * endpoint with a flag cannot be called with the wrong verb by accident, and an
 * idempotent retry is still a success rather than a conflict.
 *
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CurrencyPairRequest(@NotNull Boolean active) {
}
