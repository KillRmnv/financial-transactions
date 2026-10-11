package com.idfinance.financial_transactions.repository;

/**
 * One row of the limit report, assembled by a single query.
 *
 * <p>
 * A projection rather than an entity because the row is not a table: it is a
 * limit joined to the month's running total for its category, and there is
 * nothing to load, change or persist on it.
 *
 */
public interface LimitReportRow {

	Long getId();

	String getExpenseCategory();

	java.math.BigDecimal getLimitSum();

	java.time.Instant getLimitDatetime();

	String getCurrencyShortname();

	java.math.BigDecimal getSpent();

}
