package com.idfinance.financial_transactions.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Answer to a transaction ingestion call.
 *
 * <p>
 * {@code limitExceeded} is a {@link Boolean} rather than a primitive on
 * purpose: null means the transaction was accepted but could not be measured
 * against a limit because no exchange rate was available. Returning
 * {@code false} there would be a lie.
 */
@Schema(description = "The stored transaction and the flag that was computed for it")
public record TransactionResponse(

		@Schema(description = "Identifier of the stored row", example = "1") Long id,

		String accountFrom, String accountTo,

		@Schema(description = "ISO 4217 code of the account currency, as stored", example = "KZT") String currencyShortname,

		@Schema(description = "The amount as it arrived, in the account currency", example = "10000.45") BigDecimal sum,

		@Schema(description = "`product` or `service`", example = "product") String expenseCategory,

		@Schema(description = "ISO 8601, in the configured accounting timezone", example = "2022-01-30T00:00:00+06:00") OffsetDateTime datetime,

		@Schema(description = "The amount converted into the ledger currency, or null when no rate was available", example = "19.87") BigDecimal sumInLedger,

		@Schema(description = "Whether the month's running total went over the limit in effect. Null means no answer could be computed, which is not the same as false", example = "false") Boolean limitExceeded,

		@Schema(description = "False exactly when the flag could not be computed, usually because no rate was available", example = "true") Boolean limitCalculated) {

}
