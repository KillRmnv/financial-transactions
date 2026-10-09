package com.idfinance.financial_transactions.bdd.service;

import com.idfinance.financial_transactions.SettableClock;
import com.idfinance.financial_transactions.bdd.support.StubRatesProvider;
import com.idfinance.financial_transactions.config.LedgerPolicy;
import com.idfinance.financial_transactions.dto.LimitRequest;
import com.idfinance.financial_transactions.dto.TransactionRequest;
import com.idfinance.financial_transactions.entity.Currency;
import com.idfinance.financial_transactions.entity.ExpenseLimit;
import com.idfinance.financial_transactions.entity.ExpenseTransaction;
import com.idfinance.financial_transactions.exception.NotFoundException;
import com.idfinance.financial_transactions.repository.CurrencyRateRepository;
import com.idfinance.financial_transactions.repository.CurrencyRepository;
import com.idfinance.financial_transactions.repository.ExpenseLimitRepository;
import com.idfinance.financial_transactions.repository.ExpenseTransactionRepository;
import com.idfinance.financial_transactions.service.LimitService;
import com.idfinance.financial_transactions.service.TransactionService;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.assertj.core.api.Assertions;
import org.assertj.core.api.SoftAssertions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Steps driving the services against a real PostgreSQL, without HTTP.
 *
 * <p>
 * These are the service-level specifications: the truth-table scenario, the
 * backdated case, the concurrent posts, the conversion boundaries and the
 * currency contract. The numbers come from the specification's own tables, so a
 * feature file can be checked against the document row by row.
 */
class ServiceLevelSteps {

	private static final String COUNTERPART = "9999999999";

	@Autowired
	private SettableClock clock;

	@Autowired
	private LimitService limitService;

	@Autowired
	private TransactionService transactionService;

	@Autowired
	private ExpenseTransactionRepository expenseTransactionRepository;

	@Autowired
	private ExpenseLimitRepository expenseLimitRepository;

	@Autowired
	private CurrencyRateRepository currencyRateRepository;

	@Autowired
	private CurrencyRepository currencyRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private StubRatesProvider stubRatesProvider;

	@Autowired
	private LedgerPolicy ledgerPolicy;

	private String accountFrom;

	private String category;

	private long seedSize;

	@Before
	void cleanUp() {
		this.expenseTransactionRepository.deleteAll();
		this.expenseLimitRepository.deleteAll();
		this.currencyRateRepository.deleteAll();
		this.stubRatesProvider.setMode(StubRatesProvider.Mode.UNKNOWN);
		// Captured before any scenario inserts anything: several assertions below
		// compare the size of the
		// lookup against this number, and re-querying per assertion would hide a lookup
		// that grew during the
		// scenario.
		this.seedSize = this.currencyRepository.count();
	}

	@Given("the account {string} and the category {string}")
	void theAccountAndCategory(String accountFrom, String category) {
		this.accountFrom = accountFrom;
		this.category = category;
	}

	@Given("the clock reads {string}")
	void theClockReads(String isoInstant) {
		this.clock.set(Instant.parse(isoInstant));
	}

	@Given("a limit of {string} USD was installed on {string}")
	void aLimitWasInstalled(String sum, String isoDate) {
		// The installation date is "now" as far as the service is concerned, so moving
		// the clock is the only
		// way to place a limit in time.
		this.clock.set(LocalDate.parse(isoDate).atStartOfDay().toInstant(ZoneOffset.UTC));
		this.limitService.install(new LimitRequest(this.accountFrom, this.category, new BigDecimal(sum)));
	}

	@When("a transaction of {string} USD happened on {string}")
	void aTransactionHappened(String sum, String isoDate) {
		this.transactionService.accept(transaction(sum, "USD", this.category, isoDate));
	}

	@When("a transaction in {string} for {string} USD happened on {string}")
	void aTransactionInCurrencyHappened(String currency, String sum, String isoDate) {
		this.transactionService.accept(transaction(sum, currency, this.category, isoDate));
	}

	@When("a transaction in the category {string} for {string} USD happened on {string}")
	void aTransactionInCategoryHappened(String category, String sum, String isoDate) {
		this.transactionService.accept(transaction(sum, "USD", category, isoDate));
	}

	/**
	 * A distinct wording from the step above on purpose: two step patterns that
	 * both match one line of Gherkin is reported as ambiguous, not resolved in
	 * favour of either.
	 */
	@When("a transaction of {string} {word} was posted on {string}")
	void aTransactionInCurrencyOf(String sum, String currency, String isoDate) {
		this.transactionService.accept(transaction(sum, currency, this.category, isoDate));
	}

	@Given("the rate for {string} on {string} is {word}")
	void theRateForCurrencyIs(String currency, String isoDate, String rate) {
		Date day = Date.valueOf(LocalDate.parse(isoDate));
		this.jdbcTemplate.update(
				"insert into currency_rate (base_currency, rate_date, close, source) values (?, ?, ?, ?)", currency,
				day, new BigDecimal(rate), "seeded by a scenario");
		Assertions.assertThat(this.currencyRateRepository.findForDay(currencyOf(currency), LocalDate.parse(isoDate)))
				.as("the seeded rate of %s on %s", currency, isoDate).isPresent();
	}

	@Given("the rates provider fails")
	void theRatesProviderFails() {
		this.stubRatesProvider.setMode(StubRatesProvider.Mode.FAILING);
	}

	@Then("the flagged transactions are exactly:")
	void theFlaggedTransactionsAreExactly(DataTable table) {
		List<LocalDate> expected = table.asMaps(String.class, String.class).stream()
				.map(row -> LocalDate.parse(row.get("date"))).toList();

		List<LocalDate> actual = this.expenseTransactionRepository.findAll().stream()
				.filter((transaction) -> Boolean.TRUE.equals(transaction.getLimitExceeded()))
				.map((transaction) -> transaction.getOccurredAt().atZone(ZoneOffset.UTC).toLocalDate()).toList();

		Assertions.assertThat(actual).as("the dates of the flagged transactions").containsExactlyElementsOf(expected);
	}

	@Then("the transaction of {string} is flagged")
	void theTransactionIsFlagged(String isoDate) {
		Assertions.assertThat(flagOf(isoDate)).as("the flag of %s", isoDate).isTrue();
	}

	@Then("the flag for {string} was calculated")
	void theFlagWasCalculated(String isoDate) {
		Assertions.assertThat(transactionOf(isoDate).isFlagCalculated())
				.as("the flag of %s was computed rather than left uncalculated", isoDate).isTrue();
	}

	@Then("the rate used for {string} is {word}")
	void theRateUsedFor(String isoDate, String expected) {
		Assertions.assertThat(transactionOf(isoDate).getAppliedRate()).as("the rate stored on the transaction")
				.isEqualByComparingTo(new BigDecimal(expected));
	}

	@Then("the transaction of {string} was measured against a limit of {string} USD")
	void theTransactionWasMeasuredAgainst(String isoDate, String expected) {
		ExpenseTransaction transaction = transactionOf(isoDate);
		Assertions.assertThat(transaction.getLimitId()).as("the transaction stores the limit it was measured against")
				.isNotNull();

		Assertions.assertThat(this.expenseLimitRepository.findById(transaction.getLimitId()).orElseThrow().getSum())
				.as("the limit the flag of %s was computed against", isoDate)
				.isEqualByComparingTo(new BigDecimal(expected));
	}

	@Then("the automatic default limit is {string} USD")
	void theAutomaticDefaultLimitIs(String expected) {
		Assertions.assertThat(defaultLimit().getSum()).as("the standing fallback")
				.isEqualByComparingTo(new BigDecimal(expected));
	}

	@Then("the automatic default limit was installed at the epoch")
	void theAutomaticDefaultLimitWasInstalledAtTheEpoch() {
		// Dated at the epoch so that it is in effect for any transaction date,
		// including one from before the
		// service ever heard of the client.
		Assertions.assertThat(defaultLimit().getCreatedAt()).as("the installation date of the fallback")
				.isEqualTo(Instant.EPOCH);
	}

	@Then("the transaction of {string} is stored without a flag")
	void theTransactionIsStoredWithoutAFlag(String isoDate) {
		ExpenseTransaction transaction = transactionOf(isoDate);

		SoftAssertions softly = new SoftAssertions();
		softly.assertThat(transaction.getLimitExceeded()).as("no flag, rather than a false one").isNull();
		softly.assertThat(transaction.isFlagCalculated()).as("and the row says so explicitly").isFalse();
		softly.assertThat(transaction.getSumInLedger()).as("the converted amount is unknown").isNull();
		softly.assertAll();
	}

	private ExpenseLimit defaultLimit() {
		List<ExpenseLimit> defaults = this.expenseLimitRepository.findAll().stream()
				.filter((limit) -> limit.isDefaultLimit()).toList();

		Assertions.assertThat(defaults).as("exactly one automatic fallback per account and category").hasSize(1);
		return defaults.getFirst();
	}

	@When("{int} threads each post a transaction of {string} USD on {string}")
	void threadsEachPostATransaction(int threads, String sum, String isoDate) throws Exception {
		LocalDate day = LocalDate.parse(isoDate);
		BigDecimal amount = new BigDecimal(sum);
		CyclicBarrier startLine = new CyclicBarrier(threads);

		List<Callable<Void>> posts = IntStream.range(0, threads).<Callable<Void>>mapToObj((index) -> () -> {
			startLine.await(30, TimeUnit.SECONDS);
			this.transactionService.accept(new TransactionRequest(this.accountFrom, COUNTERPART, "USD", amount,
					this.category, day.atStartOfDay().atOffset(ZoneOffset.UTC)));
			return null;
		}).toList();

		try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
			List<Future<Void>> results = posts.stream().map(pool::submit).toList();
			for (Future<Void> result : results) {
				result.get(60, TimeUnit.SECONDS);
			}
		}
	}

	@Then("exactly {int} transactions are flagged")
	void exactlyTransactionsAreFlagged(int expected) {
		long flagged = this.expenseTransactionRepository.findAll().stream()
				.filter((transaction) -> Boolean.TRUE.equals(transaction.getLimitExceeded())).count();

		Assertions.assertThat(flagged)
				.as("the flagged count; with the row lock removed two threads read the same total and this reads 1")
				.isEqualTo(expected);
	}

	@Then("the month's total is {string} USD")
	void theMonthsTotalIs(String expected) {
		BigDecimal total = this.expenseTransactionRepository.findAll().stream().map(ExpenseTransaction::getSumInLedger)
				.reduce(BigDecimal.ZERO, BigDecimal::add);

		Assertions.assertThat(total).as("the month's running total").isEqualByComparingTo(new BigDecimal(expected));
	}

	@Then("no transaction is stored without a flag")
	void noTransactionIsStoredWithoutAFlag() {
		Assertions.assertThat(this.expenseTransactionRepository.findAll())
				.filteredOn((transaction) -> transaction.getLimitExceeded() == null)
				.as("transactions stored with an uncalculated flag").isEmpty();
	}

	/**
	 * The ledger currency and its scale are read from the running configuration and
	 * asserted against the {@code app.ledger.*} properties, so a hardcoded
	 * {@code LEDGER_CURRENCY} constant coming back would fail here rather than
	 * quietly stop the configuration from doing anything.
	 */
	@Then("the ledger is kept in {string} with {int} decimal places")
	void theLedgerIsKeptIn(String currencyCode, int amountScale) {
		Assertions.assertThat(this.ledgerPolicy.currencyCode()).as("app.ledger.currency-code as bound")
				.isEqualTo(currencyCode);
		Assertions.assertThat(this.ledgerPolicy.amountScale()).as("app.ledger.amount-scale as bound")
				.isEqualTo(amountScale);
	}

	@Then("the installed limit is denominated in {string}")
	void theInstalledLimitIsDenominatedIn(String expected) {
		Assertions.assertThat(this.expenseLimitRepository.findAll()).singleElement()
				.satisfies((limit) -> Assertions.assertThat(limit.getCurrency().getCode())
						.as("a limit points at the ledger currency rather than repeating its code")
						.isEqualTo(expected));
	}

	@Then("the currency lookup contains {string}, {string} and {string}")
	void theCurrencyLookupContains(String first, String second, String third) {
		SoftAssertions softly = new SoftAssertions();
		softly.assertThat(this.currencyRepository.findByCode(first)).isPresent();
		softly.assertThat(this.currencyRepository.findByCode(second)).isPresent();
		softly.assertThat(this.currencyRepository.findByCode(third)).isPresent();
		softly.assertAll();
	}

	@Then("the currency lookup holds more than {int} codes")
	void theCurrencyLookupHoldsMoreThan(int minimum) {
		Assertions.assertThat(this.currencyRepository.count()).as("the active ISO 4217 codes").isGreaterThan(minimum);
	}

	@Then("every code in the lookup is uppercase")
	void everyCodeInTheLookupIsUppercase() {
		List<String> lowercase = this.currencyRepository.findAll().stream().map(Currency::getCode)
				.filter((code) -> !code.equals(code.toUpperCase(Locale.ROOT))).toList();

		Assertions.assertThat(lowercase).as("codes the schema should have refused").isEmpty();
	}

	@When("a currency {string} is inserted directly")
	void aCurrencyIsInsertedDirectly(String code) {
		// ck_currency_code_uppercase and ck_currency_code_shape refuse this. The
		// expected throw cannot be
		// observed by the scenario itself, so the outcome is checked by the following
		// step instead.
		Assertions
				.assertThatThrownBy(() -> this.jdbcTemplate
						.update("insert into currency (code, display_name) values (?, ?)", code, "inserted by a test"))
				.as("the schema must refuse the code %s outright", code)
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@When("a rate is inserted directly for currency {string}")
	void aRateIsInsertedDirectlyForCurrency(String code) {
		// Through the entity this is impossible by construction, so the foreign key is
		// proved in SQL: that
		// is what refuses a hand-written INSERT bypassing the application.
		Assertions
				.assertThatThrownBy(() -> this.jdbcTemplate.update(
						"insert into currency_rate (base_currency, rate_date, close, source) values (?, ?, ?, ?)", code,
						Date.valueOf(LocalDate.of(2022, 1, 5)), BigDecimal.ONE, "stub"))
				.as("currency_rate.base_currency references currency(code), so %s has no row to point at", code)
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Then("the insert of {string} is refused by the schema")
	void theInsertIsRefusedByTheSchema(String code) {
		// The expected failure is asserted by the step that performs the insert,
		// because a scenario cannot
		// observe a throw. This step therefore documents the outcome in the report and
		// confirms the lookup
		// did not grow either way.
		Assertions.assertThat(this.currencyRepository.findByCode(code))
				.as("the lookup must hold no row for %s, whatever the insert did", code).isEmpty();
	}

	@Then("the stored transaction is in currency {string}")
	void theStoredTransactionIsInCurrency(String expected) {
		Assertions.assertThat(this.expenseTransactionRepository.findAll()).singleElement()
				.satisfies((transaction) -> Assertions.assertThat(transaction.getCurrency().getCode())
						// The row references the KZT of the lookup, so "kzt" and "KZT" cannot drift
						// into two
						// currencies.
						.isEqualTo(expected));
	}

	@Then("the currency lookup still holds the same number of codes")
	void theCurrencyLookupIsUnchanged() {
		Assertions.assertThat(this.currencyRepository.count()).as("the lookup must not grow from a request")
				.isEqualTo(this.seedSize);
	}

	/**
	 * A refusal is asserted by provoking it, not by observing one.
	 *
	 * <p>
	 * A Gherkin scenario cannot catch a throw, so a {@code When} step that posts a
	 * transaction in an unknown currency would fail the scenario as an error rather
	 * than pass it. The request is therefore made inside this step and the expected
	 * exception inspected here, which is also the only place the reason the
	 * currency is refused can be checked in detail.
	 */
	@Then("a transaction in {string} for {string} USD is refused as not found")
	void aTransactionInCurrencyIsRefused(String currency, String sum) {
		Assertions
				.assertThatThrownBy(
						() -> this.transactionService.accept(transaction(sum, currency, "product", "2022-01-05")))
				.as("a currency outside the lookup is refused rather than stored").isInstanceOf(NotFoundException.class)
				.hasMessageContaining(currency);
	}

	@Then("the refusal names {string}")
	void theRefusalNames(String code) {
		Assertions
				.assertThatThrownBy(
						() -> this.transactionService.accept(transaction("10.00", code, "product", "2022-01-05")))
				.isInstanceOf(NotFoundException.class).hasMessageContaining(code);
	}

	private TransactionRequest transaction(String sum, String currency, String category, String isoDate) {
		return new TransactionRequest(this.accountFrom, COUNTERPART, currency, new BigDecimal(sum), category,
				LocalDate.parse(isoDate).atStartOfDay().atOffset(ZoneOffset.UTC));
	}

	private ExpenseTransaction transactionOf(String isoDate) {
		LocalDate day = LocalDate.parse(isoDate);
		return this.expenseTransactionRepository.findAll().stream()
				.filter((transaction) -> transaction.getOccurredAt().atZone(ZoneOffset.UTC).toLocalDate().equals(day))
				.findFirst().orElseThrow(() -> new AssertionError("No transaction stored on " + day));
	}

	private Currency currencyOf(String code) {
		return this.currencyRepository.findByCode(code)
				.orElseThrow(() -> new AssertionError("The currency lookup holds no " + code));
	}

	private boolean flagOf(String isoDate) {
		Boolean flag = transactionOf(isoDate).getLimitExceeded();
		Assertions.assertThat(flag).as("the transaction of %s has a flag at all", isoDate).isNotNull();
		return flag;
	}

}
