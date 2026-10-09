package com.idfinance.financial_transactions.bdd.service;

import com.idfinance.financial_transactions.bdd.support.StoredWorldCleaner;
import com.idfinance.financial_transactions.bdd.support.StubRatesProvider;
import com.idfinance.financial_transactions.client.WeeklyCurrencyImport;
import com.idfinance.financial_transactions.entity.Currency;
import com.idfinance.financial_transactions.repository.CurrencyRepository;
import com.idfinance.financial_transactions.repository.ExpenseTransactionRepository;
import com.idfinance.financial_transactions.service.RatesProvider;
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
		this.expenseTransactionRepository.deleteAll();
		for (Currency added : this.currencyRepository.findAll()) {
			if (!this.codesBeforeSweep.contains(added.getCode())) {
				this.currencyRepository.delete(added);
			}
		}
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
