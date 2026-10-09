package com.idfinance.financial_transactions.bdd.rule;

import com.idfinance.financial_transactions.domain.LimitFlagRule;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.assertj.core.api.AbstractBigDecimalAssert;
import org.assertj.core.api.AbstractBooleanAssert;
import org.assertj.core.api.AbstractObjectAssert;
import org.assertj.core.api.Assertions;

/**
 * Steps for the flag rule itself, driven against the specification's truth
 * tables row by row.
 */

class LimitFlagRuleSteps {
	private BigDecimal spentSoFar = BigDecimal.ZERO;
	private BigDecimal amount = BigDecimal.ZERO;
	private BigDecimal limit;
	private BigDecimal remaining;
	private LocalDate transactionDate;
	private List<LimitFlagRule.LimitOn> installedLimits = new ArrayList<LimitFlagRule.LimitOn>();

	LimitFlagRuleSteps() {
	}

	@Before
	void reset() {
		this.spentSoFar = BigDecimal.ZERO;
		this.amount = BigDecimal.ZERO;
		this.limit = null;
		this.remaining = null;
		this.transactionDate = null;
		this.installedLimits = new ArrayList<LimitFlagRule.LimitOn>();
	}

	@Given(value = "the month has {word} USD spent so far")
	void theMonthHasSpent(String spent) {
		this.spentSoFar = new BigDecimal(spent);
	}

	@Given(value = "a limit of {word} USD is in effect")
	void aLimitIsInEffect(String limit) {
		this.limit = new BigDecimal(limit);
	}

	@When(value = "a transaction of {word} USD is accepted")
	void aTransactionIsAccepted(String amount) {
		this.amount = new BigDecimal(amount);
	}

	@When(value = "the client installs a limit of {word} USD")
	void theClientInstallsALimit(String limit) {
		this.limit = new BigDecimal(limit);
		this.remaining = this.limit.subtract(this.spentSoFar);
	}

	@Then(value = "the flag is {word}")
	void theFlagIs(String expected) {
		boolean expectedFlag = Boolean.parseBoolean(expected);
		((AbstractBooleanAssert) Assertions
				.assertThat(LimitFlagRule.isExceeded((BigDecimal) this.spentSoFar, (BigDecimal) this.amount,
						(BigDecimal) this.limit))
				.as("limit_exceeded for %s + %s against %s", this.spentSoFar, this.amount, this.limit))
				.isEqualTo(expectedFlag);
		this.spentSoFar = this.spentSoFar.add(this.amount);
	}

	@Then(value = "the remaining balance is {word} USD")
	void theRemainingBalanceIs(String expected) {
		((AbstractBigDecimalAssert) ((AbstractObjectAssert) ((Object) Assertions.assertThat(this.remaining)))
				.as("remaining balance against the freshly installed limit", new Object[0]))
				.isEqualByComparingTo(new BigDecimal(expected));
	}

	@Then(value = "a transaction of {word} USD is flagged")
	void aTransactionOfThisAmountIsFlagged(String candidate) {
		((AbstractBooleanAssert) Assertions
				.assertThat(LimitFlagRule.isExceeded((BigDecimal) this.spentSoFar,
						(BigDecimal) new BigDecimal(candidate), (BigDecimal) this.limit))
				.as("a transaction of %s USD", candidate)).isTrue();
	}

	@Then(value = "a transaction of {word} USD is not flagged")
	void aTransactionOfThisAmountIsNotFlagged(String candidate) {
		((AbstractBooleanAssert) Assertions
				.assertThat(LimitFlagRule.isExceeded((BigDecimal) this.spentSoFar,
						(BigDecimal) new BigDecimal(candidate), (BigDecimal) this.limit))
				.as("a transaction of %s USD", candidate)).isFalse();
	}

	@Given(value = "these limits were installed:")
	void theseLimitsWereInstalled(DataTable table) {
		this.installedLimits = table.asMaps(String.class, String.class).stream()
				.map(row -> new LimitFlagRule.LimitOn(LocalDate.parse((CharSequence) row.get("installedOn")),
						new BigDecimal((String) row.get("amount"))))
				.toList();
	}

	@When(value = "a transaction happens on {word}")
	void aTransactionHappensOn(String isoDate) {
		this.transactionDate = LocalDate.parse(isoDate);
	}

	@Then(value = "the limit in effect is {word}")
	void theLimitInEffectIs(String expected) {
		BigDecimal applicable = LimitFlagRule.limitInEffect(this.installedLimits, (LocalDate) this.transactionDate);
		if ("none".equals(expected)) {
			((AbstractBigDecimalAssert) ((AbstractObjectAssert) ((Object) Assertions.assertThat(applicable)))
					.as("the limit in effect on %s with none installed before it", new Object[]{this.transactionDate}))
					.isNull();
			return;
		}
		((AbstractBigDecimalAssert) ((AbstractObjectAssert) ((Object) Assertions.assertThat(applicable)))
				.as("the limit in effect on %s", new Object[]{this.transactionDate}))
				.isEqualByComparingTo(new BigDecimal(expected));
	}
}
