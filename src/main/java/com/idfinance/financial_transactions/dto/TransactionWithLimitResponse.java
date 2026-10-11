package com.idfinance.financial_transactions.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * A transaction that exceeded the limit, with the limit that was actually
 * applied.
 *
 * <p>
 * The three limit fields carry the names the specification fixes:
 * {@code limit_sum}, {@code limit_datetime} and
 * {@code limit_currency_shortname}.
 */
@Schema(description = "A transaction that exceeded a limit, with the limit it was actually measured against")
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TransactionWithLimitResponse(

		String accountFrom, String accountTo,

		@Schema(description = "ISO 4217 code of the account currency", example = "KZT") String currencyShortname,

		@Schema(description = "The amount as it arrived, in the account currency", example = "600.00") BigDecimal sum,

		@Schema(description = "`product` or `service`", example = "product") String expenseCategory,

		@Schema(description = "ISO 8601, in the configured accounting timezone", example = "2022-01-03T00:00:00+06:00") OffsetDateTime datetime,

		@Schema(description = "The amount converted into the ledger currency", example = "11.64") BigDecimal sumInLedger,

		@Schema(description = "Always true on this endpoint", example = "true") Boolean limitExceeded,

		@Schema(description = "The limit the flag was computed against. Not necessarily the one installed today, which is exactly why it travels with the row", example = "1000.00") BigDecimal limitSum,

		@Schema(description = "When that limit was installed. Null for the automatic fallback, which has no installation event of its own", example = "2022-01-10T00:00:00+06:00") OffsetDateTime limitDatetime,

		@Schema(description = "ISO 4217 code of the limit currency, which is the ledger currency", example = "USD") String limitCurrencyShortname) {

}
