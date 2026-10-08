package com.idfinance.financial_transactions.dto;

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
public record TransactionResponse(Long id, String accountFrom, String accountTo, String currencyShortname,
		BigDecimal sum, String expenseCategory, OffsetDateTime datetime, BigDecimal sumInLedger, Boolean limitExceeded,
		Boolean limitCalculated) {

}
