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
 *            the limit, or null for a category that has none — the standing
 *            default, which is a fact about the account rather than a row
 * @param expenseCategory
 *            the category code, joined rather than looked up per row
 * @param limitSum
 *            the limit amount; coalesced in the query so a category without a
 *            row still reports the configured default
 * @param limitDatetime
 *            when it was installed; the epoch for the stored fallback and null
 *            for a category with no row, and the service turns both into a null
 *            before answering
 * @param currencyShortname
 *            the ledger currency the limit is denominated in, also coalesced
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
