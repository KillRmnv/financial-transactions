package com.idfinance.financial_transactions.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.idfinance.financial_transactions.config.LedgerPolicy;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Payload of the transaction ingestion API, matching the field names the
 * specification fixes: {@code account_from}, {@code account_to},
 * {@code currency_shortname}, {@code sum}, {@code expense_category} and
 * {@code datetime}.
 *
 * <p>
 * {@code account_from} is the client's own account and identifies the limit
 * this transaction is measured against.
 *
 * <p>
 * <b>The names are not the record's.</b> A record component called
 * {@code accountFrom} would be written {@code accountFrom} on the wire by
 * default, and this javadoc would still have said the specification's names —
 * which is exactly how the two drifted apart for as long as they did: the
 * documentation was believed and nothing checked it. {@link JsonNaming} is
 * therefore the thing that makes this contract, and it says so where it is
 * declared rather than leaving a reader to infer it from field names.
 *
 * <p>
 * Each multi-word field also answers to its camelCase spelling through
 * {@link JsonAlias}. This is a service being integrated into a banking system
 * that already sends something, and accepting the older spelling costs one
 * annotation per field while making a name change non-breaking. Output is
 * snake_case only: two spellings on the way out would be a contract nobody
 * could rely on.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TransactionRequest(

		@NotBlank @Pattern(regexp = "^[0-9]{10}$") @Schema(description = "The client's own account, and the account the limit is measured against", example = "0000000123") @JsonAlias("accountFrom") String accountFrom,

		@NotBlank @Pattern(regexp = "^[0-9]{10}$") @Schema(description = "The counterparty account", example = "9999999999") @JsonAlias("accountTo") String accountTo,

		@NotBlank @Size(min = 3, max = 3) @Pattern(regexp = "^[A-Za-z]{3}$") @Schema(description = "ISO 4217 code of the account currency. Case is ignored; the accepted set is published at GET /api/v1/currencies", example = "KZT") @JsonAlias("currencyShortname") String currencyShortname,

		@NotNull @DecimalMin(value = "0.00", inclusive = false) @Digits(integer = LedgerPolicy.MAX_AMOUNT_INTEGER_DIGITS, fraction = LedgerPolicy.MAX_AMOUNT_FRACTION_DIGITS) @Schema(description = "Amount in the account currency, strictly positive, at most 11 integer and 8 fraction digits", example = "10000.45") BigDecimal sum,

		@NotBlank @Schema(description = "Exactly `product` or `service`", example = "product", allowableValues = {
				"product", "service"}) @JsonAlias("expenseCategory") String expenseCategory,

		@NotNull @Schema(description = "ISO 8601 with the offset the sender used. The month it belongs to is computed in the configured accounting timezone, not in that offset", example = "2022-01-30T00:00:00+06:00") OffsetDateTime datetime) {

}
