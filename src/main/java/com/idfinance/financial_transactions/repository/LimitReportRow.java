package com.idfinance.financial_transactions.repository;

/**
 * One row of the limit report, assembled by a single query.
 *
 * <p>
 * A projection rather than an entity because the row is not a table: it is a
 * limit joined to the month's running total for its category, and there is
 * nothing to load, change or persist on it.
 *
 * @param id
 *            the limit
 * @param expenseCategory
 *            the category code, joined rather than looked up per row
 * @param limitSum
 *            the limit amount
 * @param limitDatetime
 *            when it was installed; the epoch for the standing fallback, which
 *            the service turns into a null before answering
 * @param currencyShortname
 *            the ledger currency the limit is denominated in
 * @param spent
 *            what the month has already spent against this account and category
 */
public interface LimitReportRow {

	Long getId();

	String getExpenseCategory();

	java.math.BigDecimal getLimitSum();

	java.time.Instant getLimitDatetime();

	String getCurrencyShortname();

	java.math.BigDecimal getSpent();

}
