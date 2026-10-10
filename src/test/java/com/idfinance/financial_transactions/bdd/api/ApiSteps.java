package com.idfinance.financial_transactions.bdd.api;

import com.idfinance.financial_transactions.SettableClock;
import com.idfinance.financial_transactions.bdd.support.StoredWorldCleaner;
import com.idfinance.financial_transactions.bdd.support.WireMockRatesConfiguration.HttpRatesStub;
import com.idfinance.financial_transactions.entity.ExpenseTransaction;
import com.idfinance.financial_transactions.repository.CurrencyRateRepository;
import com.idfinance.financial_transactions.repository.ExpenseLimitRepository;
import com.idfinance.financial_transactions.repository.ExpenseTransactionRepository;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.assertj.core.api.AbstractBooleanAssert;
import org.assertj.core.api.AbstractCharSequenceAssert;
import org.assertj.core.api.AbstractIntegerAssert;
import org.assertj.core.api.AbstractIterableAssert;
import org.assertj.core.api.AbstractListAssert;
import org.assertj.core.api.AbstractObjectAssert;
import org.assertj.core.api.AbstractStringAssert;
import org.assertj.core.api.Assertions;
import org.assertj.core.api.BigDecimalAssert;
import org.assertj.core.api.BooleanAssert;
import org.assertj.core.api.IterableAssert;
import org.assertj.core.api.ListAssert;
import org.assertj.core.api.ObjectAssert;
import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.StringAssert;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Steps driving the whole API over a real HTTP port.
 *
 * <p>
 * This is the end-to-end test requirement 6 asks for: the ingestion API, the
 * limits API and the exceeded-transactions API are all called the way a client
 * would, against PostgreSQL, with the rates provider stubbed.
 */

class ApiSteps {
	private static final String COUNTERPART = "9999999999";
	private final ObjectMapper objectMapper;
	private final SettableClock clock;
	private final ExpenseTransactionRepository expenseTransactionRepository;
	private final ExpenseLimitRepository expenseLimitRepository;
	private final CurrencyRateRepository currencyRateRepository;
	private final RestClient.Builder restClientBuilder;
	private final HttpRatesStub ratesStub;
	private final StoredWorldCleaner storedWorldCleaner;
	private final Environment environment;
	private String accountFrom;
	private String category;
	private String baseUrl;
	private ResponseEntity<String> response;
	private JsonNode responseBody;

	@Before
	void cleanUp() {
		this.storedWorldCleaner.clean();
		// The stubs are rebuilt too: a scenario that published a rate must not leave it
		// behind for
		// the next one, which is the same leak the database rows would be.
		this.ratesStub.reset();
		this.response = null;
		this.responseBody = null;
	}

	@Before
	void buildClient() {
		this.baseUrl = "http://localhost:" + this.environment.getProperty("local.server.port");
	}

	@Given(value = "the account {string} and the category {string}")
	void theAccountAndCategory(String accountFrom, String category) {
		this.accountFrom = accountFrom;
		this.category = category;
	}

	@Given(value = "a limit of {string} USD was installed on {string}")
	void aLimitWasInstalled(String sum, String isoDate) {
		this.clock.set(LocalDate.parse(isoDate).atStartOfDay().toInstant(ZoneOffset.UTC));
		this.response = this.post("/api/v1/limits",
				Map.of("accountFrom", this.accountFrom, "expenseCategory", this.category, "sum", new BigDecimal(sum)));
		((ObjectAssert) ((AbstractObjectAssert) ((Object) Assertions.assertThat(this.response.getStatusCode())))
				.as("installing the limit", new Object[0])).isEqualTo(HttpStatus.CREATED);
	}

	@When(value = "a transaction of {string} USD happened on {string}")
	void aTransactionHappened(String sum, String isoDate) {
		this.postTransaction(sum, "USD", this.category, isoDate);
	}

	@When(value = "a transaction in {string} for {string} USD happened on {string}")
	void aTransactionInCurrencyHappened(String currency, String sum, String isoDate) {
		this.postTransaction(sum, currency, this.category, isoDate);
	}

	@When(value = "a transaction in the category {string} for {string} USD happened on {string}")
	void aTransactionInCategoryHappened(String category, String sum, String isoDate) {
		this.postTransaction(sum, "USD", category, isoDate);
	}

	@When(value = "a limit of {string} USD is requested on {string}")
	void aLimitIsRequested(String sum, String isoDate) {
		this.clock.set(LocalDate.parse(isoDate).atStartOfDay().toInstant(ZoneOffset.UTC));
		this.response = this.post("/api/v1/limits",
				Map.of("accountFrom", this.accountFrom, "expenseCategory", this.category, "sum", new BigDecimal(sum)));
		this.responseBody = this.readBody();
	}

	@When(value = "a transaction from account {string} of {string} USD happened on {string}")
	void aTransactionFromAccountHappened(String accountFrom, String sum, String isoDate) {
		this.postTransactionFrom(sum, "USD", this.category, isoDate, accountFrom);
	}

	@When(value = "a transaction dated {string} of {string} USD happened")
	void aTransactionDatedHappened(String moment, String sum) {
		Map<String, Object> body = this.transactionBody(sum, "USD", this.category, null, this.accountFrom);
		body.put("datetime", moment);
		this.response = this.post("/api/v1/transactions", body);
		this.responseBody = this.readBody();
	}

	@When(value = "a transaction is posted with an invalid body")
	void aTransactionIsPostedWithAnInvalidBody() {
		this.response = this.post("/api/v1/transactions", "{\"accountFrom\": \"12\"}");
		this.responseBody = this.readBody();
	}

	@When(value = "a transaction is posted with the body:")
	void aTransactionIsPostedWithTheBody(String body) {
		this.response = this.post("/api/v1/transactions", body);
		this.responseBody = this.readBody();
	}

	@When(value = "a transaction is posted with an untyped body:")
	void aTransactionIsPostedWithAnUntypedBody(String body) {
		this.response = this.exchange(((RestClient.RequestBodySpec) RestClient.builder().baseUrl(this.baseUrl).build()
				.post().uri("/api/v1/transactions", new Object[0])).body(body));
		this.responseBody = this.readBody();
	}

	@When(value = "the exceeded transactions for {string} are requested")
	void theExceededTransactionsAreRequested(String yearMonth) {
		this.response = this.exchange((RestClient.RequestHeadersSpec<?>) this.client().get().uri(
				"/api/v1/limits/exceeded?account_from=" + this.accountFrom + "&month=" + yearMonth + "-01",
				new Object[0]));
		this.responseBody = this.readBody();
	}

	@When(value = "the accepted currencies are requested")
	void theAcceptedCurrenciesAreRequested() {
		this.response = this.exchange(
				(RestClient.RequestHeadersSpec<?>) this.client().get().uri("/api/v1/currencies", new Object[0]));
		this.responseBody = this.readBody();
	}

	@Then(value = "the response is {int}")
	void theResponseIs(int expected) {
		((AbstractIntegerAssert) Assertions.assertThat(this.response.getStatusCode().value()).as("the HTTP status",
				new Object[0])).isEqualTo(expected);
	}

	@Then(value = "the response holds {int} exceeded transactions")
	void theResponseHolds(int expected) {
		((IterableAssert) ((AbstractIterableAssert) ((Object) Assertions.assertThat(this.responseBody)))
				.as("the response body is an array", new Object[0])).isInstanceOf((Class) JsonNode.class);
		((AbstractBooleanAssert) Assertions.assertThat(this.responseBody.isArray()).as("the response is a JSON array",
				new Object[0])).isTrue();
		((AbstractIntegerAssert) Assertions.assertThat(this.responseBody.size())
				.as("the number of exceeded transactions", new Object[0])).isEqualTo(expected);
	}

	@Then(value = "the exceeded transactions are on these dates:")
	void theExceededTransactionsAreOnTheseDates(DataTable table) {
		List<String> expected = table.asMaps(String.class, String.class).stream().map(row -> (String) row.get("date"))
				.toList();
		ArrayList actual = new ArrayList();
		this.responseBody.forEach(node -> actual.add(node.get("datetime").asText().substring(0, 10)));
		((ListAssert) ((AbstractListAssert) ((Object) Assertions.assertThat(actual)))
				.as("the dates of the exceeded transactions", new Object[0])).containsExactlyElementsOf(expected);
	}

	@Then(value = "the {word} exceeded transaction reports a limit of {string} USD installed on {string} in {string}")
	void anExceededTransactionReports(String ordinal, String sum, String isoDate, String currency) {
		JsonNode row = this.rowAt(ordinal);
		SoftAssertions softly = new SoftAssertions();
		((BooleanAssert) softly.assertThat(row.get("limitExceeded").asBoolean()).as("the flag of the %s row", ordinal))
				.isTrue();
		((BigDecimalAssert) softly.assertThat(row.get("limitSum").decimalValue()).as("the limit sum of the %s row",
				new Object[]{ordinal})).isEqualByComparingTo(new BigDecimal(sum));
		((StringAssert) softly.assertThat(row.get("limitDatetime").asText()).as("the limit date of the %s row",
				ordinal)).startsWith(isoDate);
		((StringAssert) softly.assertThat(row.get("limitCurrencyShortname").asText())
				.as("the limit currency of the %s row", ordinal)).isEqualTo(currency);
		softly.assertAll();
	}

	/**
	 * Posts concurrently over HTTP, which is the only place the whole chain is
	 * exercised: web layer, validation, the service, the lock and the database, all
	 * at once, against a real port.
	 *
	 * <p>
	 * Every request gets its own {@code RestClient} on purpose. A client shared
	 * between threads is safe for reads and a poor test for writes: the connection
	 * pool in front of it would serialise the posts, which is the very thing the
	 * scenario is trying to overlap.
	 *
	 * <p>
	 * The statuses are returned rather than stored in {@code this.response},
	 * because a hundred concurrent requests cannot each own one field. A 429 would
	 * be a legitimate outcome under the ingestion quota — 20 posts is under it — so
	 * any non-201 is a failure to report rather than a detail to read off.
	 */
	@When("{int} requests post a transaction of {string} USD on {string} at the same time")
	void requestsPostConcurrently(int requests, String sum, String isoDate) throws Exception {
		LocalDate day = LocalDate.parse(isoDate);
		BigDecimal amount = new BigDecimal(sum);
		CyclicBarrier startLine = new CyclicBarrier(requests);

		List<Callable<Integer>> posts = IntStream.range(0, requests).<Callable<Integer>>mapToObj((index) -> () -> {
			startLine.await(30, TimeUnit.SECONDS);
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("accountFrom", this.accountFrom);
			body.put("accountTo", COUNTERPART);
			body.put("currencyShortname", "USD");
			body.put("sum", amount);
			body.put("expenseCategory", this.category);
			body.put("datetime", day.atStartOfDay().atOffset(ZoneOffset.UTC).toString());
			try {
				ResponseEntity<String> posted = RestClient.builder().baseUrl(this.baseUrl)
						.defaultHeader("Content-Type", "application/json").build().post()
						.uri("/api/v1/transactions", new Object[0]).body(body).retrieve().toEntity(String.class);
				return posted.getStatusCode().value();
			} catch (RestClientResponseException ex) {
				return ex.getStatusCode().value();
			}
		}).toList();

		List<Integer> statuses = new ArrayList<>();
		try (ExecutorService pool = Executors.newFixedThreadPool(requests)) {
			List<Future<Integer>> results = posts.stream().map(pool::submit).toList();
			for (Future<Integer> result : results) {
				statuses.add(result.get(90, TimeUnit.SECONDS));
			}
		}

		// Set after the fact so a failing status is reported by this step rather than
		// surfacing as an
		// unrelated count mismatch three steps later.
		Assertions.assertThat(statuses).as("the status of every concurrent post; all %d are created", requests)
				.containsOnly(201);
	}

	/**
	 * Read from the database rather than from the response: with twenty concurrent
	 * posts there are twenty responses and only one answer to this question, and
	 * the row is what the twenty wrote.
	 */
	@Then("the month's total is {string} USD")
	void theMonthsTotalIs(String expected) {
		BigDecimal total = this.expenseTransactionRepository.findAll().stream().map(ExpenseTransaction::getSumInLedger)
				.reduce(BigDecimal.ZERO, BigDecimal::add);

		SoftAssertions softly = new SoftAssertions();
		softly.assertThat(total).as("the sum of every stored transaction")
				.isEqualByComparingTo(new BigDecimal(expected));
		softly.assertThat(this.expenseTransactionRepository.findAll())
				.as("no transaction stored without a calculated flag")
				.noneMatch((transaction) -> transaction.getLimitExceeded() == null);
		softly.assertAll();
	}

	@Then("exactly {int} transactions are flagged")
	void exactlyTransactionsAreFlagged(int expected) {
		long flagged = this.expenseTransactionRepository.findAll().stream()
				.filter((transaction) -> Boolean.TRUE.equals(transaction.getLimitExceeded())).count();

		Assertions.assertThat(flagged).as("the flagged count after concurrent posts").isEqualTo(expected);
	}

	@When("the limits of the account are requested")
	void theLimitsAreRequested() {
		this.response = exchange(this.client().get().uri("/api/v1/limits?account_from=" + this.accountFrom));
		this.responseBody = readBody();
	}

	@Then("the response holds {int} limits")
	void theResponseHoldsLimits(int expected) {
		Assertions.assertThat(this.responseBody.isArray()).as("the response body is a JSON array").isTrue();
		Assertions.assertThat(this.responseBody.size()).as("the number of limits").isEqualTo(expected);
	}

	@Then("the limit for {string} reports {string} spent and {string} remaining")
	void theLimitReports(String category, String spent, String remaining) {
		JsonNode row = null;
		for (JsonNode candidate : this.responseBody) {
			if (category.equals(candidate.get("expenseCategory").asText())) {
				row = candidate;
			}
		}
		Assertions.assertThat(row).as("a limit for the category %s", category).isNotNull();

		// spent and remaining come from one joined query now rather than a lookup per
		// limit, so this is
		// the assertion that would notice the aggregation being dropped or the join
		// losing the category.
		SoftAssertions softly = new SoftAssertions();
		softly.assertThat(row.get("spentUsd").decimalValue()).as("the month's total against the limit")
				.isEqualByComparingTo(new BigDecimal(spent));
		softly.assertThat(row.get("remaining").decimalValue()).as("limitSum minus spentUsd")
				.isEqualByComparingTo(new BigDecimal(remaining));
		softly.assertThat(row.get("limitCurrencyShortname").asText()).as("the ledger currency of the limit")
				.isEqualTo("USD");
		softly.assertAll();
	}

	@Given(value = "the provider answers {string} against {string} at {string} on {string}")
	void theProviderAnswers(String base, String quote, String close, String isoDate) {
		this.ratesStub.closeOn(base, quote, LocalDate.parse(isoDate), new BigDecimal(close));
	}

	@Given(value = "the provider has no close for {string} against {string} on {string} and offers {string} instead")
	void theProviderOffersPreviousClose(String base, String quote, String isoDate, String previousClose) {
		this.ratesStub.previousCloseOn(base, quote, LocalDate.parse(isoDate), new BigDecimal(previousClose));
	}

	@Given("the provider is unreachable")
	void theProviderIsUnreachable() {
		this.ratesStub.failing();
	}

	/**
	 * Posts a batch, where the rows carry an amount and a currency so the step can
	 * mix them.
	 *
	 * <p>
	 * The body is built by hand rather than through a data table for the same
	 * reason the concurrent step is: the point is the request shape, and a table
	 * would put a cucumber feature in charge of JSON.
	 */
	/**
	 * Makes the provider slow enough that the batch's rate lookups are provably in
	 * flight together, which is the condition the single-flight coordinator needs
	 * and the one an instant stub never creates.
	 */
	@Given("the provider answers slowly")
	void theProviderAnswersSlowly() {
		this.ratesStub.answeringAfter(Duration.ofMillis(400));
	}

	@When("a batch is posted with these transactions:")
	void aBatchIsPosted(DataTable table) {
		List<Map<String, Object>> body = table.asMaps(String.class, String.class).stream().map((row) -> {
			Map<String, Object> transaction = new LinkedHashMap<>();
			transaction.put("accountFrom", this.accountFrom);
			transaction.put("accountTo", COUNTERPART);
			transaction.put("currencyShortname", row.get("currency"));
			transaction.put("sum", new BigDecimal(row.get("sum")));
			transaction.put("expenseCategory", this.category);
			transaction.put("datetime",
					LocalDate.parse(row.get("date")).atStartOfDay().atOffset(ZoneOffset.UTC).toString());
			return transaction;
		}).toList();

		this.response = exchange(
				((RestClient.RequestBodySpec) this.client().post().uri("/api/v1/transactions/batch", new Object[0]))
						.body(body));
		this.responseBody = readBody();
	}

	@Then("the response holds {int} stored transactions")
	void theResponseHoldsStoredTransactions(int expected) {
		Assertions.assertThat(this.responseBody.isArray()).as("the batch response is a JSON array").isTrue();
		Assertions.assertThat(this.responseBody.size()).as("how many transactions came back").isEqualTo(expected);
	}

	@Then("the {word} stored transaction totals {string} USD")
	void theStoredTransactionTotals(String ordinal, String expected) {
		JsonNode row = rowAt(ordinal);
		SoftAssertions softly = new SoftAssertions();
		softly.assertThat(row.get("sumInLedger").decimalValue()).as("the converted amount")
				.isEqualByComparingTo(new BigDecimal(expected));
		softly.assertThat(row.get("limitExceeded").asBoolean()).as("the flag was computed").isNotNull();
		softly.assertAll();
	}

	@Then("the {word} stored transaction is in currency {string}")
	void theStoredTransactionIsInCurrency(String ordinal, String currency) {
		JsonNode row = rowAt(ordinal);
		Assertions.assertThat(row.get("currencyShortname").asText()).as("the currency of the %s transaction", ordinal)
				.isEqualTo(currency);
	}

	@Given("the provider does not publish {string} against {string}")
	void theProviderDoesNotPublish(String baseCurrency, String quoteCurrency) {
		this.ratesStub.withoutSymbol(baseCurrency, quoteCurrency);
	}

	@Then("the provider was asked {int} times for the symbol {string}")
	void theProviderWasAskedTimesFor(int times, String symbol) {
		long count = this.ratesStub.requestedSymbols().stream().filter((requested) -> requested.equals(symbol)).count();
		Assertions.assertThat(count).as("how many external calls for %s", symbol).isEqualTo(times);
	}

	@Then(value = "the provider was asked for the symbol {string}")
	void theProviderWasAskedFor(String symbol) {
		Assertions.assertThat(this.ratesStub.requestedSymbols()).as("the symbols the adapter requested")
				.contains(symbol);
	}

	@Then(value = "the only transaction is converted at {string} USD")
	void theOnlyTransactionIsConvertedAt(String ledgerSum) {
		List<ExpenseTransaction> stored = this.expenseTransactionRepository.findAll();
		Assertions.assertThat(stored).as("transactions stored").hasSize(1);
		ExpenseTransaction transaction = stored.getFirst();

		// One assertion about the stored row rather than about the response: the ledger
		// amount is
		// what the conversion produced, and reading it back is what proves the rate
		// reached the
		// adapter, was parsed out of the provider's JSON and landed on the row.
		SoftAssertions softly = new SoftAssertions();
		softly.assertThat(transaction.getSumInLedger()).as("sum multiplied by the rate the provider gave")
				.isEqualByComparingTo(new BigDecimal(ledgerSum));
		softly.assertThat(transaction.isFlagCalculated()).as("a converted transaction has a calculated flag").isTrue();
		softly.assertAll();
	}

	@Then("the only transaction is stored without a flag")
	void theOnlyTransactionIsStoredWithoutAFlag() {
		List<ExpenseTransaction> stored = this.expenseTransactionRepository.findAll();
		Assertions.assertThat(stored).as("an unreachable provider loses the rate, not the transaction").hasSize(1);
		SoftAssertions softly = new SoftAssertions();
		softly.assertThat(stored.getFirst().isFlagCalculated()).as("no flag was calculated").isFalse();
		softly.assertThat(stored.getFirst().getLimitExceeded()).as("and no verdict was invented").isNull();
		softly.assertThat(stored.getFirst().getSumInLedger()).as("the ledger amount stays absent").isNull();
		softly.assertAll();
	}

	// --- currency_pair: which currencies the daily sweep pays for ---
	//
	// Not part of the two APIs the specification asks for. It is here because the
	// only other way to change the set is SQL against the database, which is a
	// different tool with a different set of permissions — and because a failure in
	// this area means a sweep is being paid for that nobody asked for, which no
	// transaction scenario would ever notice.

	@When("the tracked currencies are listed")
	void theTrackedCurrenciesAreListed() {
		this.response = exchange(this.client().get().uri("/api/v1/currency-pairs"));
		this.responseBody = readBody();
	}

	@Then("the tracked currencies are:")
	void theTrackedCurrenciesAre(DataTable table) {
		List<String> actual = new ArrayList<>();
		this.responseBody.forEach(row -> actual.add(row.get("code").asText() + "=" + row.get("active").asBoolean()));
		List<String> expected = table.asMaps(String.class, String.class).stream()
				.map(row -> row.get("code") + "=" + Boolean.parseBoolean(row.get("active"))).toList();
		((ListAssert) ((AbstractListAssert) ((Object) Assertions.assertThat(actual)))
				.as("the tracked currencies and their state", new Object[0])).containsExactlyElementsOf(expected);
	}

	// @When only, not both. Cucumber 8 raises DuplicateStepDefinitionException for
	// a @Given and a @When
	// sharing one expression — on one method or two — and it fails the whole glue
	// class rather than the
	// one scenario. The features use "And" where the step is a precondition, which
	// is the same step.
	@When("{string} is set to be tracked")
	void theCurrencyIsSetToBeTracked(String code) {
		this.setTracked(code, true);
	}

	@When("{string} is set to be untracked")
	void theCurrencyIsSetToBeUntracked(String code) {
		this.setTracked(code, false);
	}

	@Then("the tracked currency {string} is active")
	void theTrackedCurrencyIsActive(String code) {
		((BooleanAssert) Assertions.assertThat(this.trackedStateOf(code))
				.as("whether the tracked currency %s is swept daily", code)).isTrue();
	}

	@Then("the tracked currency {string} is not active")
	void theTrackedCurrencyIsNotActive(String code) {
		((BooleanAssert) Assertions.assertThat(this.trackedStateOf(code))
				.as("whether the tracked currency %s is still swept daily", code)).isFalse();
	}

	private void setTracked(String code, boolean active) {
		this.response = this.exchange(this.client().put().uri("/api/v1/currency-pairs/" + code, new Object[0])
				.body(Map.of("active", active)));
		this.responseBody = this.readBody();
	}

	/**
	 * Read back over HTTP rather than from the repository: the claim is about what
	 * an operator would see, and a repository read would also pass if the write had
	 * never been committed and the entity were still sitting in the session.
	 */
	private boolean trackedStateOf(String code) {
		theTrackedCurrenciesAreListed();
		for (JsonNode row : this.responseBody) {
			if (code.equals(row.get("code").asText())) {
				return row.get("active").asBoolean();
			}
		}
		throw new AssertionError("No tracked currency '" + code + "' in " + this.responseBody);
	}

	@Then(value = "the response is a problem of type {string}")
	void theResponseIsAProblemOfType(String expectedSuffix) {
		((AbstractStringAssert) Assertions.assertThat(ApiSteps.contentTypeOf(this.response))
				.as("errors leave the API as application/problem+json", new Object[0]))
				.contains("application/problem+json");
		((IterableAssert) ((AbstractIterableAssert) ((Object) Assertions.assertThat(this.responseBody)))
				.as("the body carries a problem type", new Object[0])).isNotNull();
		((AbstractCharSequenceAssert) ((Object) Assertions.assertThat(this.responseBody.get("type").asText())))
				.endsWith("/" + expectedSuffix);
		((AbstractIntegerAssert) Assertions.assertThat(this.responseBody.get("status").asInt())
				.as("the status inside the problem body", new Object[0]))
				.isEqualTo(this.response.getStatusCode().value());
	}

	@Then(value = "the response lists the field violations")
	void theResponseListsTheFieldViolations() {
		((IterableAssert) ((IterableAssert) ((AbstractIterableAssert) ((Object) Assertions
				.assertThat(this.responseBody.get("errors"))))
				.as("a validation problem carries the offending fields", new Object[0])).isNotNull()).isNotEmpty();
	}

	@Then(value = "the accepted currencies include {string}, {string} and {string}")
	void theAcceptedCurrenciesInclude(String first, String second, String third) {
		List<String> published = this.toStringList();
		SoftAssertions softly = new SoftAssertions();
		((ListAssert) softly.assertThat(published).as("the published codes", new Object[0]))
				.contains(new String[]{first, second, third});
		softly.assertAll();
	}

	@Then(value = "the accepted currencies do not include {string}")
	void theAcceptedCurrenciesDoNotInclude(String unexpected) {
		((ListAssert) ((AbstractListAssert) ((Object) Assertions.assertThat(this.toStringList())))
				.as("the published list is where a client reads the accepted spelling", new Object[0]))
				.doesNotContain(new String[]{unexpected});
	}

	@Then(value = "the violation names the field {string}")
	void theViolationNamesTheField(String field) {
		JsonNode violations = this.responseBody != null ? this.responseBody.get("errors") : null;
		((IterableAssert) ((AbstractIterableAssert) ((Object) Assertions.assertThat(violations)))
				.as("the violations listed in the problem body", new Object[0])).isNotNull();
		ArrayList fields = new ArrayList();
		violations.forEach(violation -> fields.add(violation.get("field").asText()));
		((ListAssert) ((AbstractListAssert) ((Object) Assertions.assertThat(fields)))
				.as("the fields reported as invalid", new Object[0])).contains(new String[]{field});
	}

	private void postTransaction(String sum, String currency, String category, String isoDate) {
		this.postTransactionFrom(sum, currency, category, isoDate, this.accountFrom);
	}

	private void postTransactionFrom(String sum, String currency, String category, String isoDate, String accountFrom) {
		Map<String, Object> body = this.transactionBody(sum, currency, category,
				LocalDate.parse(isoDate).atStartOfDay().atOffset(ZoneOffset.UTC).toString(), accountFrom);
		this.response = this.post("/api/v1/transactions", body);
		this.responseBody = this.readBody();
	}

	private Map<String, Object> transactionBody(String sum, String currency, String category, String moment,
			String accountFrom) {
		LinkedHashMap<String, Object> body = new LinkedHashMap<String, Object>();
		body.put("accountFrom", accountFrom);
		body.put("accountTo", COUNTERPART);
		body.put("currencyShortname", currency);
		body.put("sum", new BigDecimal(sum));
		body.put("expenseCategory", category);
		body.put("datetime", moment);
		return body;
	}

	private ResponseEntity<String> post(String path, Object body) {
		return this.exchange(((RestClient.RequestBodySpec) this.client().post().uri(path, new Object[0])).body(body));
	}

	private RestClient client() {
		return this.restClientBuilder.baseUrl(this.baseUrl).defaultHeader("Content-Type", "application/json").build();
	}

	private ResponseEntity<String> exchange(RestClient.RequestHeadersSpec<?> spec) {
		try {
			return spec.retrieve().toEntity(String.class);
		} catch (RestClientResponseException ex) {
			return ((ResponseEntity.BodyBuilder) ResponseEntity.status(ex.getStatusCode())
					.headers(ex.getResponseHeaders() != null ? ex.getResponseHeaders() : new HttpHeaders()))
					.body(ex.getResponseBodyAsString());
		}
	}

	private JsonNode readBody() {
		String body;
		String string = body = this.response != null ? (String) this.response.getBody() : null;
		if (body == null) {
			return null;
		}
		try {
			return this.objectMapper.readTree(body);
		} catch (Exception ex) {
			throw new IllegalStateException("The response body was not JSON: " + body, ex);
		}
	}

	private static String contentTypeOf(ResponseEntity<String> response) {
		return response.getHeaders().getContentType() != null ? response.getHeaders().getContentType().toString() : "";
	}

	private JsonNode rowAt(String ordinal) {
		int index = switch (ordinal) {
			case "1st" -> 0;
			case "2nd" -> 1;
			case "3rd" -> 2;
			default -> throw new IllegalArgumentException("Only the first three rows are addressed, got: " + ordinal);
		};
		((AbstractIntegerAssert) Assertions.assertThat(this.responseBody.size()).as("there is a row at position %s",
				new Object[]{ordinal})).isGreaterThan(index);
		return this.responseBody.get(index);
	}

	private List<String> toStringList() {
		ArrayList<String> codes = new ArrayList<String>();
		this.responseBody.forEach(node -> codes.add(node.asText()));
		return codes;
	}
	@Autowired
	public ApiSteps(ObjectMapper objectMapper, SettableClock clock,
			ExpenseTransactionRepository expenseTransactionRepository, ExpenseLimitRepository expenseLimitRepository,
			CurrencyRateRepository currencyRateRepository, RestClient.Builder restClientBuilder,
			HttpRatesStub ratesStub, StoredWorldCleaner storedWorldCleaner, Environment environment) {
		this.objectMapper = objectMapper;
		this.clock = clock;
		this.expenseTransactionRepository = expenseTransactionRepository;
		this.expenseLimitRepository = expenseLimitRepository;
		this.currencyRateRepository = currencyRateRepository;
		this.restClientBuilder = restClientBuilder;
		this.ratesStub = ratesStub;
		this.storedWorldCleaner = storedWorldCleaner;
		this.environment = environment;
	}
}
