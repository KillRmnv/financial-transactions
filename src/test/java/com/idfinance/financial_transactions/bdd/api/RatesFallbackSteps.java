package com.idfinance.financial_transactions.bdd.api;

import com.idfinance.financial_transactions.bdd.support.StoredWorldCleaner;
import com.idfinance.financial_transactions.bdd.support.WireMockRatesConfiguration.HttpRatesStub;
import com.idfinance.financial_transactions.entity.Currency;
import com.idfinance.financial_transactions.entity.CurrencyRate;
import com.idfinance.financial_transactions.repository.CurrencyRateRepository;
import com.idfinance.financial_transactions.repository.CurrencyRepository;
import com.idfinance.financial_transactions.service.ExchangeRateService;
import com.idfinance.financial_transactions.service.RatesProvider;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.assertj.core.api.Assertions;
import org.assertj.core.api.SoftAssertions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Steps for the fallback rates provider.
 *
 * <p>
 * Separate from {@link ApiSteps} because these are about which provider
 * answered, not about a transaction: a failure in this area means a rate stored
 * under the wrong name, or a transaction that could have been priced and was
 * not, and neither shows up in a transaction scenario.
 */
class RatesFallbackSteps {

	@Autowired
	private HttpRatesStub ratesStub;

	@Autowired
	private CurrencyRateRepository currencyRateRepository;

	@Autowired
	private CurrencyRepository currencyRepository;

	@Autowired
	private StoredWorldCleaner storedWorldCleaner;

	@Autowired
	private ExchangeRateService exchangeRateService;

	@Autowired
	private RatesProvider ratesProvider;

	@Autowired
	private TransactionTemplate transactionTemplate;

	/** The pair and day the last "a rate is resolved" step asked about. */
	private String lastBase;

	private String lastQuote;

	private LocalDate lastDate;

	/** The outcome of the last seek, kept so a scenario can assert either way. */
	private ExchangeRateService.Conversion lastConversion;

	@Before
	void cleanUp() {
		this.storedWorldCleaner.clean();
		this.ratesStub.reset();
	}

	@Given("the paid provider answers {string} against {string} on {string} at {string}")
	void thePaidProviderAnswers(String base, String quote, String isoDate, String rate) {
		this.ratesStub.closeOn(base, quote, LocalDate.parse(isoDate), new BigDecimal(rate));
	}

	@Given("the paid provider has no rate for {string} against {string} on {string}")
	void thePaidProviderHasNoRate(String base, String quote, String isoDate) {
		// 404 in both orientations, because the paid adapter tries the pair as written
		// and
		// then the other way round before giving up. Stubbing one would leave the other
		// answering with whatever the previous scenario left behind.
		this.ratesStub.withoutSymbol(base, quote);
		this.ratesStub.withoutSymbol(quote, base);
	}

	@Given("the paid provider publishes no currencies")
	void thePaidProviderPublishesNoCurrencies() {
		// Not "not configured": the bean is present and answers rates, it just has no
		// list to
		// give. That is what the composite has to fall through on, and it is the only
		// way to
		// reach the fallback's list from a suite that configures the paid provider.
		this.ratesStub.paidProviderPublishesNothing();
	}

	@Given("the fallback answers {string} against {string} on {string} at {string}")
	void theFallbackAnswers(String base, String quote, String isoDate, String rate) {
		this.ratesStub.frankfurterRateOn(base, quote, LocalDate.parse(isoDate), new BigDecimal(rate));
	}

	@Given("the fallback has no rate for {string} against {string} on {string}")
	void theFallbackHasNoRate(String base, String quote, String isoDate) {
		this.ratesStub.frankfurterWithoutRate(base, quote, LocalDate.parse(isoDate));
	}

	@When("a rate is resolved for {string} against {string} on {string}")
	void aRateIsResolvedFor(String base, String quote, String isoDate) {
		this.lastBase = base;
		this.lastQuote = quote;
		this.lastDate = LocalDate.parse(isoDate);

		// Inside a transaction because that is what production does: convert stores the
		// rate it
		// fetched, and store's upsert is a modifying query that needs one. Calling
		// convert on its
		// own answers "No active transaction for update or delete query", which says
		// nothing
		// about the provider.
		this.lastConversion = this.transactionTemplate
				.execute((status) -> this.exchangeRateService.convert(BigDecimal.ONE, currencyOf(base), this.lastDate));

		Assertions.assertThat(this.lastConversion).as("a conversion for %s on %s", base, this.lastDate).isNotNull();
		Assertions.assertThat(this.lastConversion.isAvailable())
				.as("the rate resolved for %s on %s", base, this.lastDate).isTrue();
	}

	/**
	 * The same seek without the expectation, so a scenario can say "nobody had it"
	 * rather than asserting a success and negating it in prose.
	 */
	@When("a rate is sought for {string} against {string} on {string}")
	void aRateIsSoughtFor(String base, String quote, String isoDate) {
		this.lastBase = base;
		this.lastQuote = quote;
		this.lastDate = LocalDate.parse(isoDate);
		this.lastConversion = this.transactionTemplate
				.execute((status) -> this.exchangeRateService.convert(BigDecimal.ONE, currencyOf(base), this.lastDate));
	}

	@Then("the resolved rate is {string}")
	void theResolvedRateIs(String expected) {
		CurrencyRate stored = storedRate();
		SoftAssertions softly = new SoftAssertions();
		softly.assertThat(stored.getClose()).as("the close stored for %s on %s", this.lastBase, this.lastDate)
				.isNotNull().isEqualByComparingTo(new BigDecimal(expected));
		softly.assertAll();
	}

	@Then("the stored rate names {string} as its source")
	void theStoredRateNames(String expected) {
		Assertions.assertThat(storedRate().getSource()).as("currency_rate.source of the stored rate")
				.isEqualTo(expected);
	}

	@Then("the resolved rate has no previous close, because this provider has no gap to fill")
	void theResolvedRateHasNoPreviousClose() {
		// Frankfurter answers every calendar day, so the fallback in effectiveRate()
		// has
		// nothing to do here. A stored previous_close would mean the adapter invented
		// one.
		Assertions.assertThat(storedRate().getPreviousClose()).as("previous_close of the stored rate").isNull();
	}

	@Then("no rate was resolved")
	void noRateWasResolved() {
		Assertions.assertThat(this.lastConversion.isAvailable()).as("whether a rate came back at all").isFalse();
	}

	@Then("nothing was stored")
	void nothingWasStored() {
		Assertions.assertThat(this.currencyRateRepository.findAll()).as("every stored rate").isEmpty();
	}

	@Then("the fallback was asked for {string}")
	void theFallbackWasAskedFor(String expectedPair) {
		Assertions.assertThat(this.ratesStub.frankfurterRequestedPairs()).as("the pairs the fallback was asked for")
				.contains(expectedPair.toLowerCase(Locale.ROOT));
	}

	// --- the currency list ---

	@Given("the fallback publishes these currencies, including retired ones:")
	void theFallbackPublishes(DataTable table) {
		List<String> rows = new ArrayList<>();
		table.asMaps(String.class, String.class)
				.forEach((row) -> rows.add("{\"iso_code\":\"%s\",\"name\":\"%s\",\"start_date\":\"1993-01-02\"%s}"
						.formatted(row.get("code"), row.get("name"),
								retired(row.get("retired")) ? ",\"end_date\":\"2002-02-28\"" : "")));
		this.ratesStub.stubCurrencies(rows);
	}

	/**
	 * A Gherkin cell says yes or no, and {@code Boolean.parseBoolean} understands
	 * neither: it answers false for "yes" as well as for "no", so a table written
	 * with words marks nothing as retired — and the filtering under test then
	 * passes against an adapter that imported the dead codes.
	 */
	private static boolean retired(String cell) {
		return "yes".equalsIgnoreCase(cell.trim());
	}

	@Then("the published currencies are {string} and {string}")
	void thePaidProviderCurrenciesArePublished(String first, String second) {
		Assertions.assertThat(this.ratesProvider.publishedCurrencies())
				.as("the currencies the provider in play publishes").extracting(RatesProvider.PublishedCurrency::code)
				.containsExactly(first, second);
	}

	@Then("the published currencies are {string}, {string} and {string}")
	void thePublishedCurrenciesAre(String first, String second, String third) {
		Assertions.assertThat(this.ratesProvider.publishedCurrencies())
				.as("the currencies the provider in play publishes").extracting(RatesProvider.PublishedCurrency::code)
				.containsExactly(first, second, third);
	}

	private CurrencyRate storedRate() {
		return this.currencyRateRepository.findAll().stream()
				.filter((row) -> row.getBaseCurrency().getCode().equals(this.lastBase)
						&& row.getRateDate().equals(this.lastDate))
				.findFirst()
				.orElseThrow(() -> new AssertionError("No stored rate for " + this.lastBase + " on " + this.lastDate));
	}

	private Currency currencyOf(String code) {
		return this.currencyRepository.findByCode(code)
				.orElseThrow(() -> new AssertionError("The currency " + code + " is not in the lookup"));
	}

}
