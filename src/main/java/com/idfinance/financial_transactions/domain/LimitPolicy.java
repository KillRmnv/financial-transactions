package com.idfinance.financial_transactions.domain;

import java.math.BigDecimal;

/**
 * The verdict on one transaction: has this limit been passed?
 *
 * <p>
 * An interface rather than a static call for one concrete reason, not for a
 * general one: the comparison lived in this package as a pure function <em>and
 * again</em> inline in the ingestion service, so the copy that ran was not the
 * copy the tests exercised, and a change to the tested one changed nothing.
 * Depending on a declaration removes the ability to have two of them.
 *
 * <p>
 * A rule is given a {@link LimitContext} and answers yes or no. It does not
 * read the database and does not decide which transactions count.
 *
 * <p>
 * {@link #weightOf} is the other half and belongs to the same declaration on
 * purpose. A limit that weighs its spending differently is not the same limit
 * with a different figure fed to it: the weight also decides what the
 * transaction is stored as, so a weight discovered by the caller and handed to
 * the rule would be a weight stored one way and compared another.
 */
public interface LimitPolicy {

	/**
	 * @param context
	 *            the window's spending, this transaction and the limit in effect
	 * @return whether the limit has been passed. A transaction accepted without a
	 *         rate never reaches here: it has no amount to weigh, and its flag
	 *         stays uncalculated rather than invented
	 */
	boolean isExceeded(LimitContext context);

	/**
	 * What a transaction of this category counts as against a limit.
	 *
	 * <p>
	 * Written to the row when the transaction is stored, so that changing a weight
	 * later cannot rewrite a flag already decided.
	 *
	 * @return what it counts as, never null and never zero
	 */
	BigDecimal weightOf(String categoryCode);

	/** The name this rule is configured under, for the log and for the metrics. */
	String name();

}
