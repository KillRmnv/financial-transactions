package com.idfinance.financial_transactions.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * A transaction that exceeded the limit, with the limit that was actually
 * applied.
 *
 * <p>
 * The three limit fields carry the names the specification fixes:
 * {@code limit_sum}, {@code limit_datetime} and
 * {@code limit_currency_shortname}.
 */
public record TransactionWithLimitResponse(Long id, String accountFrom, String accountTo, String currencyShortname,
		BigDecimal sum, String expenseCategory, OffsetDateTime datetime, BigDecimal sumInLedger, Boolean limitExceeded,
		BigDecimal limitSum, OffsetDateTime limitDatetime, String limitCurrencyShortname) {

}
