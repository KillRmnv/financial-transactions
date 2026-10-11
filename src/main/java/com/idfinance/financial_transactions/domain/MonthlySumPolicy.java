package com.idfinance.financial_transactions.domain;

import java.math.BigDecimal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The limit is the money: the month's spending passes the amount.
 *
 * <p>
 * Every transaction counts for what it cost, and says so itself — which is what
 * keeps this the default. An operator who sets weights but leaves the policy at
 * {@code monthly-sum} has not got a weighted limit; they have configuration
 * that is not read. Honoring it here instead would mean the stored weights and
 * the compared weights came from two places that can disagree, and nothing
 * would say so.
 *
 * <p>
 * This is the rule the specification describes, so its truth table is the one
 * in {@link LimitFlagRule}, and it goes through that function rather than
 * repeating the comparison.
 */
@Component
@ConditionalOnProperty(name = "app.limits.policy", havingValue = "monthly-sum", matchIfMissing = true)
public final class MonthlySumPolicy implements LimitPolicy {

	@Override
	public boolean isExceeded(LimitContext context) {
		return LimitFlagRule.isExceeded(context.spentBefore(), context.amount(), context.limit());
	}

	@Override
	public BigDecimal weightOf(String categoryCode) {
		return BigDecimal.ONE;
	}

	@Override
	public String name() {
		return "monthly-sum";
	}

}
