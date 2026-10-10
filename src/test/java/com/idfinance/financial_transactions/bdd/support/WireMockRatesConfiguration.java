package com.idfinance.financial_transactions.bdd.support;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * {@link HttpRatesStub} holds the stubs; this class only wires the server.
 * Pointing the application at it is done by the context that imports this
 * class, through the same {@code app.rates.providers.<name>.base-url}
 * properties a deployment sets — and it has to be done there:
 * {@code @TestPropertySource} on an imported configuration class is not
 * applied, so a URL set here would be silently ignored and the adapters would
 * keep whatever defaults {@code application.yml} gives them. That failure is
 * invisible by design of the bug — one provider with a default URL registers
 * and answers, the one without stays absent, and the suite reports a missing
 * rate rather than a wrong configuration.
 */
@TestConfiguration(proxyBeanMethods = false)
public class WireMockRatesConfiguration {

	@Bean(initMethod = "start", destroyMethod = "stop")
	WireMockServer ratesProviderServer() {
		return new WireMockServer(WireMockConfiguration.options().port(WireMockPort.VALUE));
	}

	@Bean
	@Primary
	HttpRatesStub ratesStub(WireMockServer server) {
		return new HttpRatesStub(server);
	}

	/**
	 * The stubs themselves, kept apart from the wiring so a scenario can reach them
	 * without knowing how the server is configured.
	 */
	public static final class HttpRatesStub {

		private final WireMockServer server;

		/**
		 * Every close published so far, so {@link #answeringAfter} can re-register them
		 * with a delay.
		 */
		private final List<ServedRate> published = new ArrayList<>();

		HttpRatesStub(WireMockServer server) {
			this.server = server;
			this.server.stubFor(WireMock.get(WireMock.urlPathEqualTo("/symbol_search")).willReturn(WireMock.okJson("""
					{"data":[{"symbol":"USD/EUR"},{"symbol":"USD/GBP"}]}""")));
		}

		/**
		 * Publishes a close for one day, in the shape the adapter reads:
		 * {@code values[]} with {@code close} and {@code previous_close}.
		 */
		public void closeOn(String baseCurrency, String quoteCurrency, LocalDate date, BigDecimal close) {

			String body = """
					{"values":[{"datetime":"%s","close":%s,"previous_close":null}]}""".formatted(date.toString(),
					close.toPlainString());
			this.published.add(new ServedRate(baseCurrency, quoteCurrency, date, body));
			answer(baseCurrency, quoteCurrency, date, body, Duration.ZERO);
		}

		/**
		 * Answers a single date with nothing in it, which is how a closed day looks.
		 *
		 * <p>
		 * The provider sends an answer with no {@code values} rather than one with a
		 * null close: a holiday is missing from the series, not present in it with a
		 * hole in the price. Both readings reach the same fallback, and stubbing the
		 * real shape is what keeps the scenario from passing for the wrong reason.
		 */
		public void withoutCloseOn(String baseCurrency, String quoteCurrency, LocalDate date) {
			String symbol = baseCurrency + "/" + quoteCurrency;
			this.server.stubFor(WireMock.get(WireMock.urlPathEqualTo("/time_series"))
					.withQueryParam("symbol", WireMock.equalTo(symbol))
					.withQueryParam("date", WireMock.equalTo(date.toString()))
					.willReturn(WireMock.okJson("{\"meta\":{\"symbol\":\"" + symbol + "\"},\"values\":[]}")));
		}

		/**
		 * Answers a window request with the days given, newest first as the provider
		 * does.
		 *
		 * <p>
		 * The adapter builds its own {@code previous_close} out of this, because the
		 * provider does not send one — its answers carry exactly
		 * {@code datetime, open, high, low, close}. A stub that always included
		 * {@code previous_close} would have hidden that: the field was read, read
		 * correctly, and never once populated by anything real.
		 *
		 * @param dates
		 *            the days in the window, as {@code date -> close}
		 */
		public void window(String baseCurrency, String quoteCurrency, Map<LocalDate, BigDecimal> dates) {
			String symbol = baseCurrency + "/" + quoteCurrency;
			String values = dates.entrySet().stream()
					.sorted(Map.Entry.<LocalDate, BigDecimal>comparingByKey().reversed())
					.map((entry) -> """
							{"datetime":"%s","open":null,"high":null,"low":null,"close":%s}"""
							.formatted(entry.getKey().toString(), entry.getValue().toPlainString()))
					.collect(java.util.stream.Collectors.joining(","));
			this.server.stubFor(WireMock.get(WireMock.urlPathEqualTo("/time_series"))
					.withQueryParam("symbol", WireMock.equalTo(symbol))
					.withQueryParam("start_date", WireMock.matching(".+"))
					.withQueryParam("end_date", WireMock.matching(".+"))
					.willReturn(WireMock.okJson("{\"values\":[" + values + "]}")));
		}

		/**
		 * Makes one symbol answer 404, the way the provider does for a pair it does not
		 * publish.
		 *
		 * <p>
		 * Twelve Data has no {@code KZT/USD} at all while {@code USD/KZT} is there, and
		 * it says so with a 404 whose message reads like a malformed request.
		 * Reproducing that exactly is the point: a stub that only ever answers
		 * successes never learns that the inverse fallback exists, and it was the live
		 * provider that found it — the suite had been green throughout.
		 */
		public void withoutSymbol(String baseCurrency, String quoteCurrency) {
			String symbol = baseCurrency + "/" + quoteCurrency;
			this.server.stubFor(WireMock.get(WireMock.urlPathEqualTo("/time_series"))
					.withQueryParam("symbol", WireMock.equalTo(symbol)).willReturn(WireMock.notFound()));
		}

		/**
		 * Re-registers every close published so far with a delay before the answer is
		 * sent.
		 *
		 * <p>
		 * The single-flight coordinator collapses a provider call only while that call
		 * is still in flight, and a WireMock stub that answers instantly is finished
		 * before the next task begins. A scenario asserting that a pair asked about
		 * three times costs one call therefore depends on scheduling, and passes or
		 * fails on how busy the machine happens to be. Making the provider slow is what
		 * turns the overlap into the thing under test rather than a race.
		 */
		public void answeringAfter(Duration delay) {
			this.published.forEach((rate) -> answer(rate.base(), rate.quote(), rate.date(), rate.body(), delay));
		}

		private void answer(String baseCurrency, String quoteCurrency, LocalDate date, String body, Duration delay) {
			this.server.stubFor(WireMock.get(WireMock.urlPathEqualTo("/time_series"))
					.withQueryParam("symbol", WireMock.equalTo(baseCurrency + "/" + quoteCurrency))
					.withQueryParam("date", WireMock.equalTo(date.toString()))
					.willReturn(WireMock.okJson(body).withFixedDelay((int) delay.toMillis())));
		}

		/**
		 * A close as published, kept so it can be re-registered with a delay.
		 */
		private record ServedRate(String base, String quote, LocalDate date, String body) {
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
					{"values":[{"datetime":"%s","close":null,"previous_close":%s}]}""".formatted(date.toString(),
					previousClose.toPlainString());
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
		 * One pair, in the orientation asked for — which is the whole point of this
		 * provider: {@code /v2/rate/usd/kzt} answers directly, so there is no 404 to
		 * retry the other way round.
		 */
		public void frankfurterRateOn(String baseCurrency, String quoteCurrency, LocalDate date, BigDecimal rate) {
			this.server.stubFor(WireMock
					.get(WireMock.urlPathEqualTo("/v2/rate/" + baseCurrency.toLowerCase(java.util.Locale.ROOT) + "/"
							+ quoteCurrency.toLowerCase(java.util.Locale.ROOT)))
					.withQueryParam("date", WireMock.equalTo(date.toString()))
					.willReturn(WireMock.okJson("{\"date\":\"%s\",\"base\":\"%s\",\"quote\":\"%s\",\"rate\":%s}"
							.formatted(date.toString(), baseCurrency, quoteCurrency, rate.toPlainString()))));
		}

		/**
		 * A pair the provider does not publish, which it answers with a 404. That is an
		 * absence of data rather than a failure, and it is what the paid provider's
		 * 1-year history limit looks like from the outside.
		 */
		public void frankfurterWithoutRate(String baseCurrency, String quoteCurrency, LocalDate date) {
			this.server.stubFor(WireMock
					.get(WireMock.urlPathEqualTo("/v2/rate/" + baseCurrency.toLowerCase(java.util.Locale.ROOT) + "/"
							+ quoteCurrency.toLowerCase(java.util.Locale.ROOT)))
					.withQueryParam("date", WireMock.equalTo(date.toString())).willReturn(WireMock.notFound()));
		}

		/**
		 * The currency list, with retired codes deliberately mixed in. The live list
		 * carries about eighty of them — {@code DEM}, {@code FRF}, {@code RUR} — so the
		 * filtering is the part worth stubbing: an adapter that imported them would add
		 * currencies that no longer exist.
		 */
		public void frankfurterCurrencies(boolean withRetired) {
			List<String> rows = new ArrayList<>(
					List.of("{\"iso_code\":\"USD\",\"name\":\"United States Dollar\",\"start_date\":\"1948-06-21\"}",
							"{\"iso_code\":\"KZT\",\"name\":\"Kazakhstani Tenge\",\"start_date\":\"1993-12-16\"}",
							"{\"iso_code\":\"RUB\",\"name\":\"Russian Ruble\",\"start_date\":\"1998-01-02\"}"));
			if (withRetired) {
				rows.add(
						"{\"iso_code\":\"DEM\",\"name\":\"German Mark\",\"start_date\":\"1948-06-21\",\"end_date\":\"2002-02-28\"}");
				rows.add(
						"{\"iso_code\":\"RUR\",\"name\":\"Russian Ruble (1991)\",\"start_date\":\"1993-01-04\",\"end_date\":\"1998-01-02\"}");
			}
			stubCurrencies(rows);
		}

		/**
		 * The symbols the fallback adapter actually asked for.
		 */
		public List<String> frankfurterRequestedPairs() {
			return this.server
					.findAll(WireMock.getRequestedFor(WireMock.urlPathMatching("^/v2/rate/[a-z]{3}/[a-z]{3}$")))
					.stream().map((request) -> request.getUrl().substring("/v2/rate/".length()).split("\\?")[0])
					.toList();
		}

		/**
		 * An empty paid-provider list, which is what a provider with no currency
		 * endpoint looks like. With this registered the composite has to fall through
		 * to the fallback, which is the only way to reach Frankfurter's list from a
		 * suite that configures the paid one.
		 */
		public void paidProviderPublishesNothing() {
			this.server.stubFor(WireMock.get(WireMock.urlPathEqualTo("/symbol_search"))
					.willReturn(WireMock.okJson("{\"data\":[]}")));
		}

		/**
		 * The currency list, built from rows so a scenario can say exactly which codes
		 * are present and which are retired. The live list mixes in about eighty dead
		 * codes, so the filtering is the part worth controlling.
		 */
		public void stubCurrencies(List<String> rows) {
			this.server.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v2/currencies"))
					.willReturn(WireMock.okJson("[" + String.join(",", rows) + "]")));
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
			this.published.clear();
			this.server.stubFor(WireMock.get(WireMock.urlPathEqualTo("/symbol_search")).willReturn(WireMock.okJson("""
					{"data":[{"symbol":"USD/EUR"},{"symbol":"USD/GBP"}]}""")));
		}

	}

}
