package com.idfinance.financial_transactions.bdd.support;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.idfinance.financial_transactions.config.AppProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * A stand-in for the external rates provider, at the HTTP level.
 *
 * <p>
 * Requirement 6 asks for the external API to be mocked with WireMock, and
 * stubbing it is the honest way to do that: an in-process bean replaces the
 * whole client, so the request the adapter actually builds — the path, the
 * query parameters, the JSON it reads back — is never exercised. Here it is.
 *
 * <p>
 * {@link HttpRatesStub} holds the stubs; this class only wires the server and
 * points the application at it. The URL arrives through
 * {@code app.rates.base-url}, which is the same property a deployment sets, so
 * the conditional on the real client turns it on with no test-only wiring in
 * production code.
 */
@TestConfiguration(proxyBeanMethods = false)
public class WireMockRatesConfiguration {

	/**
	 * A fixed port rather than a random one: the application reads its URL from a
	 * property that this configuration has to publish before the context starts,
	 * and a dynamic port would need a two-phase start that buys nothing here.
	 * Nothing outside the test JVM can reach it.
	 */
	static final int PORT = 18080;

	@Bean(initMethod = "start", destroyMethod = "stop")
	WireMockServer ratesProviderServer() {
		return new WireMockServer(WireMockConfiguration.options().port(PORT));
	}

	@Bean
	@Primary
	HttpRatesStub ratesStub(WireMockServer server) {
		return new HttpRatesStub(server);
	}

	@Bean
	@Primary
	AppProperties ratesAppProperties(AppProperties delegate) {
		return new AppProperties(delegate.defaultLimitSum(), delegate.monthTimezone(), delegate.scheduledFetchHour(),
				new AppProperties.Rates("wiremock", "http://localhost:" + PORT, "test-key",
						delegate.rates().connectTimeout(), delegate.rates().readTimeout(), delegate.rates().attempts(),
						delegate.rates().retryBackoff()));
	}

	/**
	 * The stubs themselves, kept apart from the wiring so a scenario can reach them
	 * without knowing how the server is configured.
	 */
	public static final class HttpRatesStub {

		private final WireMockServer server;

		HttpRatesStub(WireMockServer server) {
			this.server = server;
			this.server.stubFor(WireMock.get(WireMock.urlPathEqualTo("/currency")).willReturn(WireMock.okJson("""
					{"data":[{"code":"KZT","name":"Kazakhstani Tenge"},{"code":"RUB","name":"Russian Ruble"}]}""")));
		}

		/**
		 * Publishes a close for one day, in the shape the adapter reads:
		 * {@code values[]} with {@code close} and {@code previous_close}.
		 */
		public void closeOn(String baseCurrency, String quoteCurrency, LocalDate date, BigDecimal close) {
			String symbol = baseCurrency + "/" + quoteCurrency;
			String body = """
					{"values":[{"close":%s,"previous_close":null}]}""".formatted(close.toPlainString());
			this.server.stubFor(WireMock.get(WireMock.urlPathEqualTo("/time_series"))
					.withQueryParam("symbol", WireMock.equalTo(symbol))
					.withQueryParam("date", WireMock.equalTo(date.toString())).willReturn(WireMock.okJson(body)));
		}

		/**
		 * Answers with a day that has no close of its own, so the adapter falls back to
		 * {@code previous_close} — the weekend and holiday case the specification
		 * names.
		 */
		public void previousCloseOn(String baseCurrency, String quoteCurrency, LocalDate date,
				BigDecimal previousClose) {
			String symbol = baseCurrency + "/" + quoteCurrency;
			String body = """
					{"values":[{"close":null,"previous_close":%s}]}""".formatted(previousClose.toPlainString());
			this.server.stubFor(WireMock.get(WireMock.urlPathEqualTo("/time_series"))
					.withQueryParam("symbol", WireMock.equalTo(symbol))
					.withQueryParam("date", WireMock.equalTo(date.toString())).willReturn(WireMock.okJson(body)));
		}

		/**
		 * Refuses every call, which is what an unreachable provider looks like to the
		 * client: a transport failure it has to retry and then give up on without
		 * losing the transaction.
		 */
		public void failing() {
			this.server.stubFor(WireMock.get(WireMock.urlPathEqualTo("/time_series"))
					.willReturn(WireMock.serverError().withStatus(503)));
		}

		/**
		 * The symbols the adapter actually asked for, which is what proves the request
		 * it builds reaches the provider as a symbol the provider can answer.
		 */
		public List<String> requestedSymbols() {
			return this.server.findAll(WireMock.getRequestedFor(WireMock.urlPathEqualTo("/time_series"))).stream()
					.map((request) -> request.queryParameter("symbol").firstValue()).filter(java.util.Objects::nonNull)
					.toList();
		}

		public void reset() {
			this.server.resetAll();
			this.server.stubFor(WireMock.get(WireMock.urlPathEqualTo("/currency")).willReturn(WireMock.okJson("""
					{"data":[{"code":"KZT","name":"Kazakhstani Tenge"},{"code":"RUB","name":"Russian Ruble"}]}""")));
		}

	}

}
