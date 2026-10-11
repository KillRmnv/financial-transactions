package com.idfinance.financial_transactions.domain;

import java.math.BigDecimal;

/**
 * What a limit rule is given to decide with.
 *
 * <p>
 * Everything the verdict needs and nothing about how it is worked out. The rule
 * is handed the spending already resolved to the window, because deciding
 * <em>which</em> transactions count is a different question with a different
 * answer, and it changes for different reasons than the comparison does.
 *
 * <p>
 * There is no weight here, and that is the point. A weighted limit is a
 * different rule, not the same rule told a different number: it applies its own
 * notion of what a transaction counts as, and asking the context for one would
 * put the difference between the two rules in the object both of them share.
 *
 * @param spentBefore
 *            what the window already holds for this account and category, in
 *            ledger currency, each transaction for what its stored weight makes
 *            it worth
 * @param amount
 *            what this transaction cost, in ledger currency, before any weight
 * @param limit
 *            the limit in effect on the transaction's date
 * @param accountFrom
 *            whose limit it is
 * @param expenseCategory
 *            which category it was spent in
 */
public record LimitContext(BigDecimal spentBefore, BigDecimal amount, BigDecimal limit, String accountFrom,
		String expenseCategory) {

	/** The window's total once this transaction is in it, unweighted. */
	public BigDecimal runningTotal() {
		return this.spentBefore.add(this.amount);
	}

}
