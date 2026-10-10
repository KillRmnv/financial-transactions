package com.idfinance.financial_transactions.bdd.quota;

import com.idfinance.financial_transactions.bdd.support.StoredWorldCleaner;
import com.idfinance.financial_transactions.repository.CurrencyRateRepository;
import com.idfinance.financial_transactions.repository.ExpenseLimitRepository;
import com.idfinance.financial_transactions.repository.ExpenseTransactionRepository;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Steps for the request quota.
 *
 * <p>
 * The quota is set per runner rather than in {@code application.yml} because a
 * limit that cannot be exhausted deterministically cannot be verified at all:
 * production allows 60 requests a second, which a test cannot outrun without
 * becoming slow and flaky.
 */
class RateLimitSteps {

	private final RestClient.Builder restClientBuilder;

	private final Environment environment;

	private final ExpenseTransactionRepository expenseTransactionRepository;

	private final ExpenseLimitRepository expenseLimitRepository;

	private final CurrencyRateRepository currencyRateRepository;

	private final StoredWorldCleaner storedWorldCleaner;

	private String baseUrl;

	private ResponseEntity<String> response;

	private final List<ResponseEntity<String>> responses = new ArrayList<>();

	@Before
	void cleanUp() {
		this.storedWorldCleaner.clean();
		this.baseUrl = "http://localhost:" + this.environment.getProperty("local.server.port");
		this.response = null;
		this.responses.clear();
	}

	@Given("the ingestion quota is {int} requests per hour")
	void theIngestionQuotaIs(int limitForPeriod) {
		// The numbers live in the runner's @TestPropertySource rather than here.
		// Editing the environment at
		// runtime would be worse: the limiters are created lazily and held in a map
		// inside the interceptor, so
		// a quota changed after the first request would leave the earlier limiters in
		// place and the boundary
		// would not be the one described. The values are asserted against the running
		// configuration, so the
		// feature cannot drift away from the properties that actually apply.
		Assertions.assertThat(this.environment.getProperty("app.rate-limits.ingestion.limit-for-period", Integer.class))
				.as("the quota these scenarios are measured against").isEqualTo(limitForPeriod);
		Assertions.assertThat(this.environment.getProperty("app.rate-limits.ingestion.limit-refresh-period"))
				.as("an hour long period makes the boundary deterministic").isEqualTo("1h");
	}

	@When("{int} transactions are posted for account {string}")
	void transactionsArePosted(int count, String accountFrom) {
		for (int i = 0; i < count; i++) {
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("accountFrom", accountFrom);
			body.put("accountTo", "9999999999");
			body.put("currencyShortname", "USD");
			body.put("sum", new BigDecimal("10.00"));
			body.put("expenseCategory", "product");
			body.put("datetime", "2022-01-05T00:00:00+00:00");

			ResponseEntity<String> posted = exchange(this.client().post().uri("/api/v1/transactions").body(body));
			this.responses.add(posted);
			this.response = posted;
		}
	}

	@When("another transaction is posted for account {string}")
	void anotherTransactionIsPosted(String accountFrom) {
		transactionsArePosted(1, accountFrom);
	}

	@When("the accepted currencies are requested {int} times")
	void theAcceptedCurrenciesAreRequested(int times) {
		for (int i = 0; i < times; i++) {
			ResponseEntity<String> read = exchange(this.client().get().uri("/api/v1/currencies"));
			this.responses.add(read);
			this.response = read;
		}
	}

	@Then("every one of them is answered with {int}")
	void everyOneIsAnsweredWith(int expected) {
		Assertions.assertThat(this.responses).as("the status of each of the %d requests", this.responses.size())
				.allSatisfy((each) -> Assertions.assertThat(each.getStatusCode().value()).isEqualTo(expected));
	}

	@Then("the response is {int}")
	void theResponseIs(int expected) {
		Assertions.assertThat(this.response.getStatusCode().value()).as("the HTTP status").isEqualTo(expected);
	}

	@Then("the response carries a Retry-After header")
	void theResponseCarriesARetryAfterHeader() {
		Assertions.assertThat(this.response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
				.as("a throttled client is told when to come back").isNotNull();
	}

	@Then("the response is a problem of type {string}")
	void theResponseIsAProblemOfType(String expectedSuffix) {
		String contentType = (this.response.getHeaders().getContentType() != null)
				? this.response.getHeaders().getContentType().toString()
				: "";

		Assertions.assertThat(contentType).as("errors leave the API as application/problem+json")
				.contains(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		Assertions.assertThat(this.response.getBody()).as("the refusal carries a body").isNotNull()
				.contains("/" + expectedSuffix);
	}

	private RestClient client() {
		return this.restClientBuilder.baseUrl(this.baseUrl)
				.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).build();
	}

	private ResponseEntity<String> exchange(RestClient.RequestHeadersSpec<?> spec) {
		try {
			return spec.retrieve().toEntity(String.class);
		} catch (RestClientResponseException ex) {
			return ResponseEntity.status(ex.getStatusCode())
					.headers(ex.getResponseHeaders() != null ? ex.getResponseHeaders() : new HttpHeaders())
					.body(ex.getResponseBodyAsString());
		}
	}

	@Autowired
	public RateLimitSteps(RestClient.Builder restClientBuilder, Environment environment,
			ExpenseTransactionRepository expenseTransactionRepository, ExpenseLimitRepository expenseLimitRepository,
			CurrencyRateRepository currencyRateRepository, StoredWorldCleaner storedWorldCleaner) {
		this.restClientBuilder = restClientBuilder;
		this.environment = environment;
		this.expenseTransactionRepository = expenseTransactionRepository;
		this.expenseLimitRepository = expenseLimitRepository;
		this.currencyRateRepository = currencyRateRepository;
		this.storedWorldCleaner = storedWorldCleaner;
	}
}
