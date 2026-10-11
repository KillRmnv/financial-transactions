package com.idfinance.financial_transactions.bdd.rule;

import com.idfinance.financial_transactions.config.AppProperties;
import com.idfinance.financial_transactions.domain.LimitContext;
import com.idfinance.financial_transactions.domain.LimitPolicy;
import com.idfinance.financial_transactions.domain.MonthlySumPolicy;
import com.idfinance.financial_transactions.domain.WeightedPolicy;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.Map;
import org.assertj.core.api.Assertions;

/**
 * The two rules that can measure a limit.
 *
 * <p>
 * Both are decisions over four numbers and a category, with no database and no
 * clock, so they are asked here rather than through a transaction: the
 * specification's truth table already covers the comparison itself in
 * {@link LimitFlagRuleSteps}, and what is left to say is which rule asks the
 * transaction to count for what.
 */
class LimitPolicySteps {

	private LimitPolicy policy = new MonthlySumPolicy();

	private BigDecimal spentBefore = BigDecimal.ZERO;

	private BigDecimal amount = BigDecimal.ONE;

	private BigDecimal limit = new BigDecimal("1000");

	private String category = "product";

	/**
	 * Builds a weighted rule over a configuration holding the given weights.
	 *
	 * <p>
	 * Hand-built rather than bound, because binding it would mean a Spring context
	 * and a database to decide between two multiplications. The rest of
	 * {@code AppProperties} is filled with values this rule never reads; naming
	 * them is cheaper than a constructor that takes only the part that matters and
	 * would then not be the record the application actually binds.
	 */
	@Given("the weighted rule is in force with {string} weighing {string}")
	void theWeightedRuleIsInForce(String categoryCode, String weight) {
		AppProperties properties = new AppProperties(new BigDecimal("1000"), ZoneId.of("UTC"), 3,
				new AppProperties.Rates(null, null, 1, null, 1, Map.of()),
				new AppProperties.Limits("weighted", Map.of(categoryCode, new BigDecimal(weight))));
		this.policy = new WeightedPolicy(properties);
		this.category = categoryCode;
	}

	@Given("the monthly-sum rule is in force, with {string} weighing {string} configured anyway")
	void theMonthlySumRuleIsInForce(String categoryCode, String weight) {
		AppProperties properties = new AppProperties(new BigDecimal("1000"), ZoneId.of("UTC"), 3,
				new AppProperties.Rates(null, null, 1, null, 1, Map.of()),
				new AppProperties.Limits("monthly-sum", Map.of(categoryCode, new BigDecimal(weight))));
		this.policy = new MonthlySumPolicy();
		this.category = categoryCode;
	}

	@Given("the month has already spent {string} USD")
	void theMonthHasSpent(String spent) {
		this.spentBefore = new BigDecimal(spent);
	}

	@Given("a transaction of {string} USD is posted")
	void aTransactionIsPosted(String posted) {
		this.amount = new BigDecimal(posted);
	}

	@Given("the limit in effect is {string} USD")
	void theLimitInEffectIs(String value) {
		this.limit = new BigDecimal(value);
	}

	@Given("the transaction is in category {string}")
	void theTransactionIsInCategory(String code) {
		this.category = code;
	}

	private boolean verdict() {
		return this.policy
				.isExceeded(new LimitContext(this.spentBefore, this.amount, this.limit, "0000000462", this.category));
	}

	@Then("the rule says the limit is passed")
	void theRuleSaysTheLimitIsPassed() {
		Assertions.assertThat(verdict()).as("the verdict under the %s rule", this.policy.name()).isTrue();
	}

	@Then("the rule says the limit is not passed")
	void theRuleSaysTheLimitIsNotPassed() {
		Assertions.assertThat(verdict()).as("the verdict under the %s rule", this.policy.name()).isFalse();
	}

	@Then("a transaction in {string} counts as {string} against its limit")
	void aTransactionCountsAs(String categoryCode, String weight) {
		Assertions.assertThat(this.policy.weightOf(categoryCode))
				.as("what the %s rule gives %s", this.policy.name(), categoryCode)
				.isEqualByComparingTo(new BigDecimal(weight));
	}

}
