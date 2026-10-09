package com.idfinance.financial_transactions.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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
public record TransactionRequest(

		@NotBlank @Pattern(regexp = "^[0-9]{10}$") @Schema(description = "The client's own account, and the account the limit is measured against", example = "0000000123") String accountFrom,

		@NotBlank @Pattern(regexp = "^[0-9]{10}$") @Schema(description = "The counterparty account", example = "9999999999") String accountTo,

		@NotBlank @Size(min = 3, max = 3) @Pattern(regexp = "^[A-Za-z]{3}$") @Schema(description = "ISO 4217 code of the account currency. Case is ignored; the accepted set is published at GET /api/v1/currencies", example = "KZT") String currencyShortname,

		@NotNull @DecimalMin(value = "0.00", inclusive = false) @Digits(integer = 12, fraction = 2) @Schema(description = "Amount in the account currency, strictly positive, at most two decimal places", example = "10000.45") BigDecimal sum,

		@NotBlank @Schema(description = "Exactly `product` or `service`", example = "product", allowableValues = {
				"product", "service"}) String expenseCategory,

		@NotNull @Schema(description = "ISO 8601 with the offset the sender used. The month it belongs to is computed in the configured accounting timezone, not in that offset", example = "2022-01-30T00:00:00+06:00") OffsetDateTime datetime) {

}
