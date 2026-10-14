package com.idfinance.financial_transactions.bdd.rule;

import com.idfinance.financial_transactions.config.LedgerPolicy;
import com.idfinance.financial_transactions.domain.MonthBoundary;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import org.assertj.core.api.Assertions;

/**
 * Steps for the decisions that are pure functions: which month a moment belongs
 * to, and what the ledger policy does with a configuration or an amount.
 *
 * <p>
 * Nothing here touches a database or a clock bean, which is why these features
 * run in a fraction of a second. A rule that can be expressed as a function
 * belongs in one, and this is where it gets checked against its edges.
 */
class PureDecisionSteps {

	private ZoneId zone = ZoneId.of("UTC");

	private Instant moment;

	private LedgerPolicy ledgerPolicy;

	@Given("the accounting timezone is {string}")
	void theAccountingTimezoneIs(String zoneId) {
		this.zone = ZoneId.of(zoneId);
	}

	@When("a transaction happens at {string}")
	void aTransactionHappensAt(String moment) {
		try {
			this.moment = Instant.parse(moment);
		} catch (DateTimeParseException notAnInstant) {
			// Transactions arrive with an offset, which is an OffsetDateTime and not an
			// Instant. Accepting
			// both spellings is the point of the boundary scenarios.
			this.moment = OffsetDateTime.parse(moment).toInstant();
		}
	}

	@Then("it belongs to the month {string}")
	void itBelongsToTheMonth(String expected) {
		Assertions.assertThat(MonthBoundary.monthOf(this.moment, this.zone))
				.as("the month of %s in %s", this.moment, this.zone).isEqualTo(LocalDate.parse(expected));
	}

	@Then("the instant in UTC is {string}")
	void theInstantInUtcIs(String expected) {
		Assertions.assertThat(utcFormatter().format(this.moment)).as("%s written back in UTC", this.moment)
				.isEqualTo(expected);
	}

	@When("the ledger is configured with currency {string}")
	void theLedgerIsConfiguredWithCurrency(String code) {
		this.ledgerPolicy = ledger(code, 2);
	}

	@When("the ledger is configured with a blank currency")
	void theLedgerIsConfiguredWithABlankCurrency() {
		this.ledgerPolicy = ledger("", 2);
	}

	@When("the ledger is configured with a scale of {int}")
	void theLedgerIsConfiguredWithScale(int scale) {
		this.ledgerPolicy = ledger("USD", scale);
	}

	@Given("the ledger is configured with currency {string} and a scale of {int}")
	void theLedgerIsConfiguredWith(String code, int scale) {
		this.ledgerPolicy = ledger(code, scale);
	}

	/**
	 * A refusal cannot be observed by the scenario, so the construction records the
	 * absence and the following step decides whether that was expected.
	 *
	 * @return the policy, or null when the configuration was refused
	 */
	private static LedgerPolicy ledger(String code, int scale) {
		try {
			return new LedgerPolicy(code, scale);
		} catch (IllegalArgumentException refused) {
			return null;
		}
	}

	@Then("the configuration is {word}")
	void theConfigurationIs(String outcome) {
		Assertions.assertThat(this.ledgerPolicy == null).as("the ledger configuration was refused")
				.isEqualTo("refused".equals(outcome));
	}

	@Then("{double} rounds to {string}")
	void amountRoundsTo(double amount, String expected) {
		Assertions.assertThat(this.ledgerPolicy.round(BigDecimal.valueOf(amount)))
				.as("%s rounded by the ledger", amount).isEqualByComparingTo(new BigDecimal(expected));
	}

	@Then("{string} is a ledger currency")
	void codeIsALedgerCurrency(String code) {
		Assertions.assertThat(this.ledgerPolicy.isLedgerCurrency(code)).as("%s is the ledger currency", code).isTrue();
	}

	@Then("{string} is not a ledger currency")
	void codeIsNotALedgerCurrency(String code) {
		Assertions.assertThat(this.ledgerPolicy.isLedgerCurrency(code)).as("%s is the ledger currency", code).isFalse();
	}

	@Then("{string} is within the storage ceiling")
	void amountIsWithinTheStorageCeiling(String amount) {
		Assertions.assertThat(this.ledgerPolicy.exceedsStorageCeiling(new BigDecimal(amount)))
				.as("%s against the widest value numeric(20, 8) holds", amount).isFalse();
	}

	@Then("{string} is past the storage ceiling")
	void amountIsPastTheStorageCeiling(String amount) {
		Assertions.assertThat(this.ledgerPolicy.exceedsStorageCeiling(new BigDecimal(amount)))
				.as("%s against the widest value numeric(20, 8) holds", amount).isTrue();
	}

	private static DateTimeFormatter utcFormatter() {
		return DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneId.of("UTC"));
	}

}
