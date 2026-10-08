package com.idfinance.financial_transactions.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Payload of the transaction ingestion API, matching the field names the
 * specification fixes: {@code account_from}, {@code account_to},
 * {@code currency_shortname}, {@code sum}, {@code expense_category} and
 * {@code datetime}.
 *
 * <p>
 * {@code account_from} is the client's own account and identifies the limit
 * this transaction is measured against.
 */
public record TransactionRequest(@NotBlank @Pattern(regexp = "^[0-9]{10}$") String accountFrom,

		@NotBlank @Pattern(regexp = "^[0-9]{10}$") String accountTo,

		@NotBlank @Size(min = 3, max = 3) @Pattern(regexp = "^[A-Za-z]{3}$") String currencyShortname,

		@NotNull @DecimalMin(value = "0.00", inclusive = false) @Digits(integer = 12, fraction = 2) BigDecimal sum,

		@NotBlank String expenseCategory,

		@NotNull OffsetDateTime datetime) {

}
