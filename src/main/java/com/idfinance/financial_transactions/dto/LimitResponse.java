package com.idfinance.financial_transactions.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * A limit, with the amount already spent in the current month.
 *
 * <p>
 * {@code remaining} is negative once the month goes over the limit, which is a
 * legitimate state rather than an error: the reporting endpoint answers 200 for
 * it.
 */
public record LimitResponse(Long id, String accountFrom, String expenseCategory, BigDecimal limitSum,
		String limitCurrencyShortname, OffsetDateTime limitDatetime, BigDecimal spentUsd, BigDecimal remaining) {

}
