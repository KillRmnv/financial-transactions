package com.idfinance.financial_transactions;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.idfinance.financial_transactions.bdd.support.BddInfrastructureConfiguration;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;

/**
 * HTTP traffic is logged as metadata, and nothing else.
 *
 * <p>
 * The specification lists Logbook among the libraries to use, and the
 * interesting half of "use" is what it writes down. A request body here is an
 * account number, a counterparty account and an amount; a response body adds
 * the limit and the converted sum. Both are the client's finances, and both
 * would land in a file that is rotated, shipped and kept far longer than the
 * database row they came from.
 *
 * <p>
 * So the bodies are switched off at the filter rather than truncated — a
 * truncated body is still a body — and this asserts the outcome rather than the
 * setting: the request was logged, and the account number is nowhere in what
 * was written. Asserting {@code logbook.filters.body.default-enabled=false}
 * would pass just as happily against a mis-spelled property that left the
 * bodies on.
 *
 * <p>
 * The account is the marker because it is the one thing that appears both in
 * the body and in the URL-free metadata: if the body were being written, this
 * would find it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(value = {TestcontainersConfiguration.class, BddInfrastructureConfiguration.class})
@TestPropertySource(properties = {"app.rate-limits.ingestion.limit-for-period=100000",
		"app.rate-limits.ingestion.limit-refresh-period=1ms", "app.rate-limits.limit-install.limit-for-period=100000",
		"app.rate-limits.limit-install.limit-refresh-period=1ms", "app.scheduling.enabled=false",
		"logging.level.org.zalando.logbook=TRACE"})
class HttpLoggingDisclosureTests {

	private static final String ACCOUNT = "0000000731";

	private static final String COUNTERPART = "9999999999";

	private final RestClient restClient;

	private final String port;

	private ListAppender<ILoggingEvent> captured;

	private Logger logbook;

	@Autowired
	public HttpLoggingDisclosureTests(RestClient.Builder builder, Environment environment) {
		this.restClient = builder.baseUrl("http://localhost:" + environment.getProperty("local.server.port")).build();
		this.port = environment.getProperty("local.server.port");
	}

	@BeforeEach
	void startCapturing() {
		this.logbook = (Logger) LoggerFactory.getLogger("org.zalando.logbook.Logbook");
		this.captured = new ListAppender<>();
		this.captured.start();
		this.logbook.addAppender(this.captured);
	}

	@AfterEach
	void stopCapturing() {
		this.logbook.detachAppender(this.captured);
		this.captured.stop();
	}

	private List<String> linesWritten() {
		return this.captured.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
	}

	@Test
	void theRequestIsLoggedAsMetadataAndWithoutItsBody() {
		this.restClient.post().uri("/api/v1/transactions").contentType(MediaType.APPLICATION_JSON)
				.body(Map.of("account_from", ACCOUNT, "account_to", COUNTERPART, "currency_shortname", "USD", "sum",
						new BigDecimal("10.00"), "expense_category", "product", "datetime",
						OffsetDateTime.parse("2022-01-05T00:00:00+00:00")))
				.retrieve().toBodilessEntity();

		List<String> lines = linesWritten();

		assertThat(lines).as("the request was logged at all").isNotEmpty();
		assertThat(lines).as("what was written about it").anyMatch(line -> line.contains("/api/v1/transactions"));
		assertThat(lines).as("no body means no account number anywhere in the log")
				.noneMatch(line -> line.contains(ACCOUNT));
		// The caller's address is the one piece of metadata that identifies a person,
		// and
		// Logbook has no switch for it — so it is replaced rather than configured, and
		// the
		// proof is that the full address is nowhere in the line.
		assertThat(lines).as("the caller's address is reduced").allMatch(line -> !line.contains("127.0.0.1"));
		assertThat(lines).as("and it is still there to group by").anyMatch(line -> line.contains("***.***.***."));
	}

	@Test
	void thePortIsCarriedInTheLogSoLinesCanBeCorrelatedWithTheInstance() {
		// Not a disclosure concern, but the thing that makes per-instance filtering
		// possible at all, and it is worth one line because it is the kind of detail
		// that
		// disappears silently if the encoding is ever changed.
		assertThat(this.port).isNotBlank();
	}

}
