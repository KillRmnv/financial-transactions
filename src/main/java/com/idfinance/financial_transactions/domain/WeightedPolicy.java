package com.idfinance.financial_transactions.domain;

import com.idfinance.financial_transactions.config.AppProperties;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The limit is what the spending is <em>worth</em>, which is not always what it
 * cost.
 *
 * <p>
 * A service expense may count half against the month's allowance while a
 * product expense counts all of it, so one number can hold a client to
 * transactions of two quite different kinds. The weights are configuration per
 * category; a category absent from the map counts for its full value, so
 * setting one weight does not mean enumerating the others.
 *
 * <p>
 * The weight is applied here rather than in {@link LimitContext}, and that is
 * the whole difference between this rule and {@link MonthlySumPolicy}: what a
 * limit counts is a business decision, while whether the comparison is strict
 * is not. The comparison itself is the specification's, taken from
 * {@link LimitFlagRule}, so the truth table holds for both rules and neither
 * can drift into a second opinion about {@code >} versus {@code >=}.
 *
 * <p>
 * The stored weight is what the running total is summed by, so a weight changed
 * in June does not rewrite what January's transactions meant.
 *
 * <p>
 * <b>What this is not.</b> A limit is per category and every transaction in a
 * category carries the same weight, so {@code sum(w·x) > L} and
 * {@code sum(x) > L/w} are the same rule written twice: weighting a category is
 * dividing its limit by the weight. That is not a reason to drop this, and it
 * is a reason not to believe it measures more than it does. What it buys is a
 * limit stated in the client's own units. It becomes a different measurement
 * only when the weight differs <em>within</em> a category, or a transaction
 * counts against more than one limit — and neither is expressible against
 * today's schema, where a row carries a single {@code limit_id}.
 */
@Component
@ConditionalOnProperty(name = "app.limits.policy", havingValue = "weighted")
@RequiredArgsConstructor
public final class WeightedPolicy implements LimitPolicy {

	private final AppProperties properties;

	@Override
	public boolean isExceeded(LimitContext context) {
		BigDecimal weighted = context.amount().multiply(weightOf(context.expenseCategory()));
		return LimitFlagRule.isExceeded(context.spentBefore(), weighted, context.limit());
	}

	@Override
	public BigDecimal weightOf(String categoryCode) {
		return this.properties.limits().weightOf(categoryCode);
	}

	@Override
	public String name() {
		return "weighted";
	}

}
