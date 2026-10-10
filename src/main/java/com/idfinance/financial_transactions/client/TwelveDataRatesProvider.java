package com.idfinance.financial_transactions.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.idfinance.financial_transactions.config.AppProperties;
import com.idfinance.financial_transactions.domain.ExchangeRate;
import com.idfinance.financial_transactions.service.RatesProvider;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Wire adapter for <a href="https://twelvedata.com">Twelve Data</a>, and for no
 * other provider.
 *
 * <ul>
 * <li>{@code GET /time_series?symbol=BASE/QUOTE&interval=1day&date=YYYY-MM-DD}
 * — answers
 * {@code {"values": [{"datetime": …, "close": …, "previous_close": …}]}}.</li>
 * <li>{@code GET /currency} — answers
 * {@code {"data": [{"code": …, "name": …}]}}, used by the weekly sweep of the
 * currency lookup.</li>
 * </ul>
 *
 * <p>
 * <strong>Why this provider and not another.</strong> The specification asks
 * for the day's {@code close}, falling back to {@code previous_close} when
 * there is none — a weekend or a holiday. That pair is a property of an
 * exchange session, and Twelve Data's {@code /time_series} is the one endpoint
 * among the three providers named in the task that returns both, for free, for
 * a dated symbol. Alpha Vantage's free {@code CURRENCY_EXCHANGE_RATE} returns a
 * live rate rather than a session close; Open Exchange Rates updates hourly
 * from a reference rate and has no time series at all on its free plan. Adding
 * either would mean a different adapter <em>and</em> a different meaning for
 * {@code previous_close}, which is why the work is not a URL change.
 *
 * <p>
 * Retries and timeouts come from configuration, because the ingestion path must
 * not hang when the provider is slow. The retry is resilience4j's {@link Retry}
 * core API rather than its {@code @Retry} aspect: an aspect needs the Spring
 * starter and AOP, and the policy is already built once in the constructor
 * instead of being scattered over annotated methods.
 *
 * <p>
 * The client builds its own {@link RestClient} rather than injecting a builder:
 * Boot 4 does not contribute a {@code RestClient.Builder} bean.
 */
@Component
@ConditionalOnProperty(name = "app.rates.base-url")
@Slf4j
public final class TwelveDataRatesProvider implements RatesProvider {

	private final RestClient restClient;

	private final AppProperties properties;

	private final Retry retry;

	public TwelveDataRatesProvider(AppProperties properties) {
		this.properties = properties;
		AppProperties.Rates rates = properties.rates();
		this.restClient = RestClient.builder().baseUrl(rates.baseUrl()).requestFactory(requestFactory(rates))
				.defaultHeader("apikey", rates.apiKey()).build();
		this.retry = Retry.of("rates-provider", retryConfig(rates));
	}

	/**
	 * The retry policy is built once, from configuration, rather than assembled per
	 * call.
	 *
	 * <p>
	 * The core resilience4j API is used instead of the {@code @Retry} aspect on
	 * purpose: an aspect would need {@code resilience4j-spring-boot} plus AOP, and
	 * this class already has to be written as an adapter. The decorator gives the
	 * same behaviour — bounded attempts, a fixed pause between them, and the last
	 * failure propagated once the attempts run out.
	 */
	private static RetryConfig retryConfig(AppProperties.Rates rates) {
		return RetryConfig.custom().maxAttempts(Math.max(1, rates.attempts()))
				.intervalFunction(IntervalFunction.of(rates.retryBackoff()))
				.retryOnException(failure -> true).build();
	}

	/**
	 * The provider name is configuration, not a constant. It is written into
	 * {@code currency_rate.source}, so it has to be set per deployment: a staging
	 * instance talking to a sandbox and production talking to a paid tier must be
	 * distinguishable in stored data.
	 */
	@Override
	public String name() {
		return this.properties.rates().source();
	}

	@Override
	public ExchangeRate rateFor(String baseCurrency, String quoteCurrency, LocalDate date) {
		String symbol = baseCurrency + "/" + quoteCurrency;
		return this.retry.executeSupplier(() -> {
			log.debug("Asking {} for {}/{}", this.properties.rates().source(), symbol, date);
			return fetchOnce(symbol, baseCurrency, quoteCurrency, date);
		});
	}

	private ExchangeRate fetchOnce(String symbol, String baseCurrency, String quoteCurrency, LocalDate date) {
		TimeSeriesResponse response = this.restClient.get()
				.uri(uriBuilder -> uriBuilder.path("/time_series").queryParam("symbol", symbol)
						.queryParam("interval", "1day").queryParam("date", date.toString())
						.queryParam("apikey", this.properties.rates().apiKey()).build())
				.retrieve().body(TimeSeriesResponse.class);

		if (response == null || response.values() == null || response.values().isEmpty()) {
			return null;
		}
		TimeSeriesResponse.Value first = response.values().getFirst();
		if (first == null || (first.close() == null && first.previousClose() == null)) {
			return null;
		}
		return new ExchangeRate(baseCurrency, quoteCurrency, date, first.close(), first.previousClose());
	}

	@Override
	public List<PublishedCurrency> publishedCurrencies() {
		CurrenciesResponse response = this.restClient
				.get().uri(uriBuilder -> uriBuilder.path("/currency")
						.queryParam("apikey", this.properties.rates().apiKey()).build())
				.retrieve().body(CurrenciesResponse.class);

		if (response == null || response.data() == null) {
			return List.of();
		}
		return response.data().stream().map(entry -> new PublishedCurrency(entry.code(), entry.name())).toList();
	}

	private static ClientHttpRequestFactory requestFactory(AppProperties.Rates rates) {
		var factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(rates.connectTimeout());
		factory.setReadTimeout(rates.readTimeout());
		return factory;
	}

	record CurrenciesResponse(List<Currency> data) {

		/**
		 * @param code
		 *            ISO 4217 code as the provider spells it
		 * @param name
		 *            human readable name
		 */
		record Currency(String code, String name) {
		}

	}

	/**
	 * Shape of the {@code /time_series} response.
	 *
	 * <p>
	 * Only the fields the specification needs are mapped. The endpoint answers more
	 * than this, and letting the rest in would put a second, unmapped version of
	 * the same rate on the record.
	 *
	 * @param values
	 *            one entry per day
	 */
	record TimeSeriesResponse(List<Value> values) {

		/**
		 * @param close
		 *            the close of that day
		 * @param previousClose
		 *            the last close before it, the fallback the specification asks for
		 *            on weekends and holidays; spelled {@code previous_close} on the
		 *            wire and camel cased here
		 */
		record Value(BigDecimal close, @JsonProperty("previous_close") BigDecimal previousClose) {
		}

	}

}
