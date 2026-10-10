package com.idfinance.financial_transactions.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * A limit, with the amount already spent in the current month.
 *
 * <p>
 * {@code remaining} is negative once the month goes over the limit, which is a
 * legitimate state rather than an error: the reporting endpoint answers 200 for
 * it.
 *
 * <p>
 * The spent amount used to be called {@code spentUsd}, which named a currency
 * the ledger need not be: {@code app.ledger.currency-code} is configuration,
 * and a field called after USD would have kept saying USD after the ledger
 * moved.
 */
@Schema(description = "A limit and what has been spent against it in the current month")
public record LimitResponse(

		String accountFrom,

		@Schema(description = "`product` or `service`", example = "product") String expenseCategory,

		@Schema(description = "The limit amount", example = "2000.00") BigDecimal limitSum,

		@Schema(description = "ISO 4217 code of the limit currency, which is the ledger currency", example = "USD") String limitCurrencyShortname,

		@Schema(description = "When the limit was installed. Null for the automatic fallback, which has no installation event at all", example = "2022-01-10T00:00:00+06:00") OffsetDateTime limitDatetime,

		/**
		 * Says the automatic fallback outright rather than leaving the client to infer
		 * it from a null date, which reads as an unknown value rather than as "there
		 * was no installation".
		 */
		@Schema(description = "True for the automatic 1000 USD fallback, which nobody installed. The only reason its date is null", example = "false") Boolean limitDefault,

		@Schema(description = "ISO 4217 code that spentLedger and remaining are expressed in, which is the ledger currency", example = "USD") String ledgerCurrency,

		@Schema(description = "Sum of this month's transactions for the account and category, in the ledger currency", example = "1100.00") BigDecimal spentLedger,

		@Schema(description = "limitSum minus spentLedger. Negative once the month goes over the limit", example = "900.00") BigDecimal remaining) {

}
