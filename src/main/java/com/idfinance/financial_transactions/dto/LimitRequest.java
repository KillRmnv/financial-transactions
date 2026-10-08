package com.idfinance.financial_transactions.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;

/**
 * Payload for installing a limit.
 *
 * <p>
 * There is deliberately no date field: the specification states the service
 * always sets the current time itself, which is what makes a client unable to
 * backdate or postdate a limit.
 */
public record LimitRequest(@NotBlank @Pattern(regexp = "^[0-9]{10}$") String accountFrom,

		@NotBlank String expenseCategory,

		@NotNull @DecimalMin(value = "0.00", inclusive = false) @Digits(integer = 12, fraction = 2) BigDecimal sum) {

}
