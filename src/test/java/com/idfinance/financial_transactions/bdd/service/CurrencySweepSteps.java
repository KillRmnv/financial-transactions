package com.idfinance.financial_transactions.bdd.service;

import com.idfinance.financial_transactions.bdd.support.StoredWorldCleaner;
import com.idfinance.financial_transactions.bdd.support.StubRatesProvider;
import com.idfinance.financial_transactions.client.WeeklyCurrencyImport;
import com.idfinance.financial_transactions.entity.Currency;
import com.idfinance.financial_transactions.entity.ScheduledJob;
import com.idfinance.financial_transactions.repository.CurrencyRepository;
import com.idfinance.financial_transactions.repository.ExpenseTransactionRepository;
import com.idfinance.financial_transactions.service.RatesProvider;
import com.idfinance.financial_transactions.service.ScheduledJobService;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.assertj.core.api.Assertions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Steps for the weekly sweep of the currency lookup.
 *
 * <p>
 * Separate from {@code ServiceLevelSteps} because this is the only scenario set
 * that writes to the {@code currency} table, and the cleanup it needs is the
 * reason: every other scenario leaves the lookup alone, this one cannot.
 *
 * <p>
 * The sweep is called directly rather than by waiting for its cron. That also
 * removes the one thing that could make these scenarios lie: {@code
 * app.scheduling.enabled} is not switched off in the test contexts, so the
 * schedule is genuinely registered and would fire on its own at Monday 04:30.
 * Diffing the lookup around this call, and restoring it afterwards, keeps a
 * stray fire from being able to change an answer.
 */
class CurrencySweepSteps {

	/**
	 * Injected through a provider on purpose. The bean is conditional — it needs a
	 * {@code RatesProvider} to exist at all — and a hard {@code @Autowired} on a
	 * missing bean would fail the context, taking down every other scenario in this
	 * suite rather than just these. This way a missing bean fails here, with a
	 * message that says which condition did not hold.
	 */
	@Autowired
	private ObjectProvider<WeeklyCurrencyImport> weeklyCurrencyImport;

	@Autowired
	private CurrencyRepository currencyRepository;

	@Autowired
	private StoredWorldCleaner storedWorldCleaner;

	@Autowired
	private ExpenseTransactionRepository expenseTransactionRepository;

	@Autowired
	private StubRatesProvider stubRatesProvider;

	@Autowired
	private ScheduledJobService scheduledJobService;

	/**
	 * The codes present before this scenario ran, so the sweep's effect is a diff.
	 */
	private Set<String> codesBeforeSweep;

	@Before
	void rememberTheLookup() {
		// The tables are cleaned through the shared cleaner rather than here, so that a
		// scenario in this file starts in the same state as every other one and leaves
		// the same state behind. Two scenarios in one suite that each clean differently
		// is how a cache survives a delete.
		this.storedWorldCleaner.clean();
		this.codesBeforeSweep = codesInLookup();
		this.stubRatesProvider.setPublishedCurrencies();
		this.stubRatesProvider.setCurrencyListingFails(false);
		this.stubRatesProvider.resetCurrencyListingCalls();
	}

	/**
	 * Restores the lookup to what the migration seeded.
	 *
	 * <p>
	 * The currency table is the one table no other scenario cleans, so leaving a
	 * row behind would change the size that {@code CurrencyContractTest} compares
	 * against its own baseline.
	 *
	 * <p>
	 * The transactions go first. A scenario that gets a new currency accepted posts
	 * a transaction referencing it, and {@code expense_transaction.currency_id} is
	 * a foreign key: deleting the currency while that row still exists fails on the
	 * constraint, and the row that was meant to disappear stays behind to be found
	 * by the next scenario. The other suites clean transactions in a
	 * {@code @Before}, which runs after this {@code @After} — the two hooks are
	 * simply in the wrong order for this one.
	 */
	@After
	void restoreTheLookup() {
		// The flag first: it is shared state that this file mutates and nothing else
		// restores. Left
		// off, every later scenario in every suite would have a sweep that silently
		// does nothing,
		// and the failure would land in whichever one happened to run next.
		this.scheduledJobService.setEnabled(ScheduledJob.Names.WEEKLY_CURRENCY_IMPORT, true);
		this.scheduledJobService.setEnabled(ScheduledJob.Names.DAILY_RATES, true);

		this.expenseTransactionRepository.deleteAll();
		for (Currency added : this.currencyRepository.findAll()) {
			if (!this.codesBeforeSweep.contains(added.getCode())) {
				this.currencyRepository.delete(added);
			}
		}
	}

	@Given("the weekly currency import is switched off")
	void theWeeklyCurrencyImportIsSwitchedOff() {
		this.scheduledJobService.setEnabled(ScheduledJob.Names.WEEKLY_CURRENCY_IMPORT, false);
	}

	@Given("the weekly currency import is switched on")
	void theWeeklyCurrencyImportIsSwitchedOn() {
		this.scheduledJobService.setEnabled(ScheduledJob.Names.WEEKLY_CURRENCY_IMPORT, true);
	}

	/**
	 * The provider was not asked at all, rather than the lookup not having grown.
	 * The list this scenario publishes is non-empty, so a job that ignored the flag
	 * would both add a currency and make the call — but only the call is the thing
	 * being paid for.
	 */
	@Then("the provider was not asked for its currency list")
	void theProviderWasNotAskedForItsCurrencyList() {
		Assertions.assertThat(this.stubRatesProvider.currencyListingCalls())
				.as("calls to the provider's currency listing, which is what the switch exists to avoid").isZero();
	}

	@Then("the provider was asked for its currency list")
	void theProviderWasAskedForItsCurrencyList() {
		Assertions.assertThat(this.stubRatesProvider.currencyListingCalls())
				.as("calls to the provider's currency listing").isGreaterThan(0);
	}

	@Given("the provider publishes these currencies:")
	void theProviderPublishes(DataTable table) {
		List<RatesProvider.PublishedCurrency> published = table.asMaps(String.class, String.class).stream()
				.map((row) -> new RatesProvider.PublishedCurrency(row.get("code"), row.get("name"))).toList();

		this.stubRatesProvider.setPublishedCurrencies(published.toArray(new RatesProvider.PublishedCurrency[0]));
	}

	/**
	 * A single currency as a step rather than a table row, because a padded code
	 * has to be written with its spaces visible: inside an {@code Examples} cell
	 * the quotes and the padding both become part of the value.
	 */
	@Given("the provider publishes the currency {string} as {string}")
	void theProviderPublishesOne(String code, String name) {
		this.stubRatesProvider.setPublishedCurrencies(new RatesProvider.PublishedCurrency(code, name));
	}

	@Given("the provider cannot list its currencies")
	void theProviderCannotList() {
		this.stubRatesProvider.setCurrencyListingFails(true);
	}

	@When("the weekly sweep runs")
	void theWeeklySweepRuns() {
		this.weeklyCurrencyImport.getObject().refreshCurrencies();
	}

	@Then("the currency lookup grew by {int} codes")
	void theCurrencyLookupGrewBy(int expected) {
		Set<String> now = codesInLookup();

		Set<String> added = new LinkedHashSet<>(now);
		added.removeAll(this.codesBeforeSweep);

		Assertions.assertThat(added).as("the codes this run added").hasSize(expected);
	}

	@Then("the currency {string} is in the lookup")
	void theCurrencyIsInTheLookup(String code) {
		Assertions.assertThat(this.currencyRepository.findByCode(code)).as("the lookup holds %s after the sweep", code)
				.isPresent();
	}

	@Then("the currency {string} is not in the lookup")
	void theCurrencyIsNotInTheLookup(String code) {
		Assertions.assertThat(this.currencyRepository.findByCode(code)).as("the lookup must not hold %s", code)
				.isEmpty();
	}

	private Set<String> codesInLookup() {
		return this.currencyRepository.findAll().stream().map(Currency::getCode)
				.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
	}

}
