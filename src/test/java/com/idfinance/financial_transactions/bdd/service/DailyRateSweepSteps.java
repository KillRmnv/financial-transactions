package com.idfinance.financial_transactions.bdd.service;

import com.idfinance.financial_transactions.bdd.support.StubRatesProvider;
import com.idfinance.financial_transactions.entity.CurrencyRate;
import com.idfinance.financial_transactions.repository.CurrencyRateRepository;
import com.idfinance.financial_transactions.repository.CurrencyRepository;
import com.idfinance.financial_transactions.service.ScheduledRatesFetcher;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.time.LocalDate;
import java.util.Optional;
import org.assertj.core.api.Assertions;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Steps for the daily job that prefetches the rates of the tracked currencies.
 *
 * <p>
 * Its own file rather than an addition to {@code CurrencySweepSteps}, which is
 * about the <em>weekly</em> import of currency codes. The two jobs answer
 * different questions — which currencies exist, and what they are worth — and
 * their {@code scheduled_job} rows are switched independently, so one scenario
 * set turning one off must not be read as turning the other off.
 *
 * <p>
 * Called directly rather than by waiting for the cron, for the same reason the
 * weekly one is: the schedule is genuinely registered in this suite, and a
 * scenario asserting what the job writes has to be able to say when it ran.
 */
class DailyRateSweepSteps {

	private final ScheduledRatesFetcher scheduledRatesFetcher;

	private final StubRatesProvider stubRatesProvider;

	private final CurrencyRateRepository currencyRateRepository;

	private final CurrencyRepository currencyRepository;

	/**
	 * Forgets what the provider was asked for before this scenario.
	 *
	 * <p>
	 * The lock is not reset here and cannot be: {@code refreshDaily} holds it for
	 * ten minutes, ShedLock also remembers a lock it has taken in memory, and a
	 * second call inside that window is refused without an error. One scenario per
	 * run is what the annotation allows, which is the same property that stops two
	 * replicas paying twice for the same rate.
	 */
	@Before
	void forgetEarlierRateLookups() {
		this.stubRatesProvider.resetRateCalls();
	}

	@Given("the provider answers every rate lookup")
	void theProviderAnswersEveryRateLookup() {
		this.stubRatesProvider.setMode(StubRatesProvider.Mode.ANSWERING);
	}

	@When("the daily rate sweep runs")
	void theDailyRateSweepRuns() {
		this.scheduledRatesFetcher.refreshDaily();
	}

	/**
	 * A mid-scenario reset, because the assertion is about the step that follows
	 * rather than about the whole scenario: the sweep itself must make its calls,
	 * and those are the very calls being ruled out afterwards.
	 */
	@When("the provider's rate lookups are forgotten")
	void theProvidersRateLookupsAreForgotten() {
		this.stubRatesProvider.resetRateCalls();
	}

	@Then("a rate for {string} is stored for {string}")
	void aRateIsStored(String code, String isoDate) {
		Assertions.assertThat(storedOn(code, isoDate)).as("the rate of %s stored for %s", code, isoDate).isPresent();
	}

	@Then("no rate for {string} is stored for {string}")
	void noRateIsStored(String code, String isoDate) {
		Assertions.assertThat(storedOn(code, isoDate))
				.as("the sweep must not have written %s for %s, because nobody asks for that day", code, isoDate)
				.isEmpty();
	}

	@Then("the provider was asked for a rate")
	void theProviderWasAskedForARate() {
		Assertions.assertThat(this.stubRatesProvider.rateCalls()).as("calls made by the sweep itself").isGreaterThan(0);
	}

	@Then("the provider was asked for rates {int} times")
	void theProviderWasAskedForRates(int expected) {
		Assertions.assertThat(this.stubRatesProvider.rateCalls())
				.as("calls to the provider for a rate; KZT is stored for %s", storedDays("KZT")).isEqualTo(expected);
	}

	/**
	 * Every day a rate is stored for, so a failed assertion says what the sweep
	 * actually wrote instead of only that the provider was reached.
	 */
	private String storedDays(String code) {
		return this.currencyRepository.findByCode(code)
				.map((currency) -> this.currencyRateRepository.findAll().stream()
						.filter((rate) -> rate.getBaseCurrency().getId().equals(currency.getId()))
						.map((rate) -> rate.getRateDate().toString()).toList().toString())
				.orElse("no such currency");
	}

	private Optional<CurrencyRate> storedOn(String code, String isoDate) {
		return this.currencyRepository.findByCode(code)
				.flatMap((currency) -> this.currencyRateRepository.findForDay(currency, LocalDate.parse(isoDate)));
	}

	@Autowired
	public DailyRateSweepSteps(ScheduledRatesFetcher scheduledRatesFetcher, StubRatesProvider stubRatesProvider,
			CurrencyRateRepository currencyRateRepository, CurrencyRepository currencyRepository) {
		this.scheduledRatesFetcher = scheduledRatesFetcher;
		this.stubRatesProvider = stubRatesProvider;
		this.currencyRateRepository = currencyRateRepository;
		this.currencyRepository = currencyRepository;
	}
}
