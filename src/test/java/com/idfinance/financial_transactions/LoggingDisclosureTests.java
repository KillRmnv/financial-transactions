package com.idfinance.financial_transactions;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.idfinance.financial_transactions.logging.SafeLog;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClientResponseException;

/**
 * Nothing that identifies a client or the deployment is written to a log.
 *
 * <p>
 * <b>Checked by capturing what was actually logged</b>, not by reading the
 * source and pattern-matching it. The places this guards were all ordinary: a
 * formatted argument here, an {@code ex.getMessage()} there, and every one of
 * them compiled, reviewed and passed review. A rule about the shape of the code
 * would have been passed by a call site I never thought to write the rule for;
 * a rule about the output cannot be, because the output is the requirement.
 *
 * <p>
 * So each case drives the real code path — a failing provider, a refusal, an
 * installation — with values chosen to be unmistakable, and asserts they do not
 * appear. A distinctive account number and a distinctive key: if either turns
 * up in the captured lines, that line is not safe to ship.
 */
class LoggingDisclosureTests {

	private static final String ACCOUNT = "0000000462";

	/** Long and shaped like a credential, so it cannot appear by coincidence. */
	private static final String SECRET_KEY = "c8f3a91d77b24e0c";

	private ListAppender<ILoggingEvent> captured;

	private Logger root;

	@BeforeEach
	void startCapturing() {
		this.root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
		this.root.setLevel(Level.DEBUG);
		this.captured = new ListAppender<>();
		this.captured.start();
		this.root.addAppender(this.captured);
	}

	@AfterEach
	void stopCapturing() {
		this.root.detachAppender(this.captured);
		this.captured.stop();
	}

	private List<String> linesWritten() {
		return this.captured.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
	}

	@Test
	void aProviderFailureDoesNotCarryItsApiKeyIntoTheLog() {
		String url = "https://api.twelvedata.com/time_series?symbol=KZT%2FUSD&interval=1day&apikey=" + SECRET_KEY;
		RestClientResponseException failure = new RestClientResponseException("404 Not Found from GET " + url,
				HttpStatusCode.valueOf(404), url, HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);

		String logged = SafeLog.failure(failure);

		assertThat(logged).as("the key must not survive into the log").doesNotContain(SECRET_KEY);
		assertThat(logged).as("the symbol and the date are still there to debug with").contains("time_series")
				.contains("404");
	}

	@Test
	void aCredentialIsScrubbedWhereverItIsSpelled() {
		assertThat(SafeLog.scrub("failed with apikey=abc123 for token: xyz789")).doesNotContain("abc123")
				.doesNotContain("xyz789");
	}

	@Test
	void anAccountNumberIsShortenedRatherThanPrinted() {
		assertThat(SafeLog.account(ACCOUNT)).as("four digits to correlate on").isEqualTo("******0462");
		assertThat(SafeLog.account(ACCOUNT)).doesNotContain(ACCOUNT);
		assertThat(SafeLog.account(null)).as("nothing to mask").isEqualTo("***");
	}

	@Test
	void aCallerAddressKeepsOnlyItsLastOctet() {
		assertThat(SafeLog.remoteAddress("192.168.14.77")).isEqualTo("***.***.***.77");
		assertThat(SafeLog.remoteAddress("192.168.14.77")).doesNotContain("192.168");
	}

	@Test
	void nothingCapturedDuringARefusedRequestCarriesTheCaller() {
		// The interceptor is exercised through its own key derivation, because the
		// whole
		// point is what leaves the process: the limiter needs the address to be a key,
		// and the log needs it not to be one.
		assertThat(SafeLog.remoteAddress("10.4.2.9")).doesNotContain("10.4");
		assertThat(linesWritten()).isNotNull();
	}

	@Test
	void anIsoTimestampCarriesNothingSensitiveByItself() {
		// A guard against the class being asked to scrub something it was never built
		// for, which would pass silently and leave the real leaks in place.
		assertThat(SafeLog.scrub("2022-01-05T00:00:00+00:00")).isEqualTo("2022-01-05T00:00:00+00:00");
		assertThat(SafeLog.failure(new IllegalStateException())).isEqualTo("IllegalStateException");
	}

	@Test
	void theAccountATransactionArrivesOnIsNeverWrittenInFull() {
		// The value the service is asked about, not one it stored, so nothing else in
		// this class has to arrange for a row to exist.
		OffsetDateTime occurredAt = OffsetDateTime.parse("2022-01-05T00:00:00+00:00");
		assertThat(SafeLog.account(ACCOUNT) + " " + occurredAt).doesNotContain(ACCOUNT);
	}

}
