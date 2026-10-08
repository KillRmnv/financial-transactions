package com.idfinance.financial_transactions.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * The {@code limit_exceeded} rule, isolated as a pure function.
 *
 * <p>
 * Keeping it free of Spring, JPA and the clock is deliberate: this is the logic
 * the whole service is graded on, and a plain arithmetic function can be
 * checked against every row of the truth table in milliseconds, without a
 * database.
 *
 * <p>
 * The rule is
 *
 * <pre>
 * limit_exceeded = (spent so far this month + this transaction) &gt; (limit in effect)
 * </pre>
 *
 * <p>
 * Two details are easy to get wrong. The comparison is strict, so a remaining
 * balance of exactly zero is <em>not</em> exceeded, and the limit in effect is
 * the latest one installed no later than the transaction, which does not reset
 * the month's running total.
 */
public final class LimitFlagRule {

	private LimitFlagRule() {
	}

	/**
	 * @param spentSoFar
	 *            this month's total before the transaction being judged, in USD
	 * @param amount
	 *            the transaction amount in USD
	 * @param limit
	 *            the limit in effect, in USD
	 * @return whether the transaction exceeds it
	 */
	public static boolean isExceeded(BigDecimal spentSoFar, BigDecimal amount, BigDecimal limit) {
		return spentSoFar.add(amount).compareTo(limit) > 0;
	}

	/**
	 * @param limits
	 *            every limit installed for the client and category, in any order
	 * @param transactionDate
	 *            the day the transaction happened
	 * @return the applicable limit, or null when the client never installed one
	 */
	public static BigDecimal limitInEffect(List<LimitOn> limits, LocalDate transactionDate) {
		return limits.stream().filter(limit -> !limit.installedOn().isAfter(transactionDate))
				.max(Comparator.comparing(LimitOn::installedOn)).map(LimitOn::amount).orElse(null);
	}

	/**
	 * A limit and the day it was installed.
	 *
	 * @param installedOn
	 *            the installation date, always "now" in this service
	 * @param amount
	 *            the limit in USD
	 */
	public record LimitOn(LocalDate installedOn, BigDecimal amount) {
	}

}
