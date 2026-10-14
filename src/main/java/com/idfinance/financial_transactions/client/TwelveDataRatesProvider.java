package com.idfinance.financial_transactions.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.idfinance.financial_transactions.config.AppProperties;
import com.idfinance.financial_transactions.config.LedgerPolicy;
import com.idfinance.financial_transactions.domain.ExchangeRate;
import com.idfinance.financial_transactions.logging.SafeLog;
import com.idfinance.financial_transactions.service.ConditionalOnProviderConfigured;
import com.idfinance.financial_transactions.service.RatesProvider;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;

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
 * Retries and timeouts come from configuration, because the ingestion path must
 * not hang when the provider is slow. The retry is resilience4j's {@link Retry}
 * core API rather than its {@code @Retry} aspect: an aspect needs the Spring
 * starter and AOP, and the policy is already built once in the constructor
 * instead of being scattered over annotated methods.
 *
 */
@Component
@ConditionalOnProviderConfigured("twelvedata")
@Slf4j
public final class TwelveDataRatesProvider implements RatesProvider {

	private final RestClient restClient;

	private final AppProperties properties;

	private final LedgerPolicy ledgerPolicy;

	private final Retry retry;

	/**
	 * How many rows to ask for. The provider caps this well below any useful page
	 * size and offers no pagination, so the number is a ceiling rather than a
	 * choice.
	 */
	private static final String SEARCH_PAGE = "500";

	/** A currency pair as the provider spells it. */
	private static final String BASE_QUOTE = "^[A-Z]{3}/[A-Z]{3}$";

	public TwelveDataRatesProvider(AppProperties properties, LedgerPolicy ledgerPolicy) {
		this.properties = properties;
		this.ledgerPolicy = ledgerPolicy;
		AppProperties.Rates.Provider provider = settings();
		AppProperties.Rates rates = properties.rates();
		this.restClient = RestClient.builder().baseUrl(provider.baseUrl()).requestFactory(requestFactory(rates))
				.defaultHeader("apikey", provider.apiKey() == null ? "" : provider.apiKey()).build();
		this.retry = Retry.of("rates-provider", retryConfig(rates));
	}

	/**
	 * The retry policy is built once, from configuration, rather than assembled per
	 * call.
	 */
	private static RetryConfig retryConfig(AppProperties.Rates rates) {
		return RetryConfig.custom().maxAttempts(Math.max(1, rates.attempts()))
				.intervalFunction(IntervalFunction.of(rates.retryBackoff())).retryOnException(failure -> true).build();
	}

	/**
	 * The provider name is configuration, not a constant. It is written into
	 * {@code currency_rate.source}, so it has to be set per deployment: a staging
	 * instance talking to a sandbox and production talking to a paid tier must be
	 * distinguishable in stored data.
	 */
	@Override
	public String name() {
		return source();
	}

	/**
	 * Which block of {@code app.rates.providers} this adapter is. The only place
	 * outside {@code application.yml} the provider's key appears, and it is here
	 * because the class is that provider.
	 */
	private static final String PROVIDER = "twelvedata";

	/** This provider's settings, which are empty until its block is configured. */
	private AppProperties.Rates.Provider settings() {
		return this.properties.rates().providers().get(PROVIDER);
	}

	/** The credential, sent as the provider expects it. */
	private String apiKey() {
		AppProperties.Rates.Provider provider = settings();
		return provider == null ? "" : provider.apiKey();
	}

	/** What is written into {@code currency_rate.source} for a rate from here. */
	private String source() {
		AppProperties.Rates.Provider provider = settings();
		return provider == null ? "none" : provider.name();
	}

	/**
	 * The rate as published, and the inverse if the provider does not publish this
	 * orientation.
	 *
	 * <p>
	 * Twelve Data has no some codes. Asking for the other side and inverting is
	 * what makes the specification's own pair work against this provider.
	 *
	 * <p>
	 * The inverse costs one more request only when the direct one fails, which is
	 * the uncommon case: {@code RUB/USD}, {@code EUR/USD} and most others are
	 * published the way they are asked for.
	 */
	@Override
	public Optional<ExchangeRate> rateFor(String baseCurrency, String quoteCurrency, LocalDate date) {
		Optional<ExchangeRate> direct = askFor(baseCurrency + "/" + quoteCurrency, baseCurrency, quoteCurrency, date);
		if (direct.isPresent()) {
			return direct;
		}

		log.info("{} publishes no {}/{}, asking for the pair the other way round", source(), baseCurrency,
				quoteCurrency);
		return askFor(quoteCurrency + "/" + baseCurrency, quoteCurrency, baseCurrency, date)
				.map(ExchangeRate::inverted);
	}

	/**
	 * One attempt at one symbol, with the retry policy around it.
	 *
	 */
	private Optional<ExchangeRate> askFor(String symbol, String baseCurrency, String quoteCurrency, LocalDate date) {
		return Optional.ofNullable(this.retry.executeSupplier(() -> {
			log.debug("Asking {} for {}/{}", source(), symbol, date);
			return fetchOnce(symbol, baseCurrency, quoteCurrency, date);
		}));
	}

	/**
	 * The close of that day, and the last close before it as the fallback.
	 *
	 * <p>
	 * <strong>The provider does not send {@code previous_close}.</strong> Its
	 * answer carries exactly {@code datetime, open, high, low, close} — checked
	 * against the live API, not assumed.
	 *
	 * <p>
	 * The fallback is computed here, out of what the provider does send: one more
	 * request over a window ending on the day before, whose latest point is the
	 * last close before it. Forex pairs trade through weekends, so the gap is rare
	 * — but a holiday or a provider gap closes a series, and the specification asks
	 * for that day to be priced rather than skipped.
	 *
	 * <p>
	 * A window rather than a single "previous" query because the provider has no
	 * such endpoint, and asking one day at a time would need to know in advance
	 * which day the last one was.
	 */
	private ExchangeRate fetchOnce(String symbol, String baseCurrency, String quoteCurrency, LocalDate date) {
		TimeSeriesResponse ofTheDay = request(symbol, (builder) -> builder.queryParam("date", date.toString()));
		if (ofTheDay == null) {
			return null;
		}

		TimeSeriesResponse.Value value = valueFor(ofTheDay, date);
		if (value != null && value.close() != null) {
			return new ExchangeRate(baseCurrency, quoteCurrency, date, value.close(), value.previousClose(), name());
		}

		BigDecimal previous = lastCloseBefore(symbol, date);
		if (previous == null) {
			return null;
		}
		log.debug("{} has no close for {} on {}, falling back to {}", source(), symbol, date, previous);
		return new ExchangeRate(baseCurrency, quoteCurrency, date, null, previous, name());
	}

	/**
	 * How the provider says a date is outside the history its plan keeps.
	 *
	 * <p>
	 * Matched on the body rather than on the status alone: 400 also covers a
	 * request this adapter got wrong, and treating that as "no data" would turn a
	 * bug into a transaction quietly stored without a flag.
	 */
	private static final String NO_DATA = "No data is available";

	/**
	 * The last close strictly before the day asked for, or null when the window
	 * holds none.
	 */
	private BigDecimal lastCloseBefore(String symbol, LocalDate date) {
		LocalDate from = date.minusDays(properties.rates().fallbackDays());
		TimeSeriesResponse window = request(symbol, (builder) -> builder.queryParam("start_date", from.toString())
				.queryParam("end_date", date.minusDays(1).toString()));
		if (window == null || window.values() == null) {
			return null;
		}
		// The newest of the points in the window, found by date rather than by
		// position: this
		// provider answers newest first, so the last element happens to be the oldest,
		// and a
		// provider answering the other way round would then silently price the day from
		// the far
		// end of the window. Comparing the dates makes that an error instead of a wrong
		// number.
		return window.values().stream()
				.filter(value -> value != null && value.datetime() != null && value.close() != null)
				.filter(value -> !value.datetime().isBefore(from) && value.datetime().isBefore(date))
				.map(TimeSeriesResponse.Value::datetime).max(LocalDate::compareTo)
				.map(latest -> window.values().stream()
						.filter(value -> value.datetime() != null && value.datetime().equals(latest))
						.map(TimeSeriesResponse.Value::close).findFirst().orElse(null))
				.orElse(null);
	}

	/**
	 * One call, with the provider's "no such symbol" answer treated as no answer
	 * rather than as a failure.
	 */
	private TimeSeriesResponse request(String symbol, Function<UriBuilder, UriBuilder> query) {
		try {
			return this.restClient.get()
					.uri(uriBuilder -> query.apply(uriBuilder.path("/time_series").queryParam("symbol", symbol)
							.queryParam("interval", "1day").queryParam("apikey", apiKey())).build())
					.retrieve().body(TimeSeriesResponse.class);
		} catch (HttpClientErrorException.NotFound ex) {
			log.debug("{} has no symbol {}: {}", source(), symbol, SafeLog.failure(ex));
			return null;
		} catch (HttpClientErrorException.BadRequest ex) {
			if (ex.getResponseBodyAsString().contains(NO_DATA)) {
				log.debug("{} has no data before the date asked for: {}", source(), SafeLog.failure(ex));
				return null;
			}
			throw ex;
		}
	}

	/**
	 * The entry describing one day, whichever end of the answer the provider listed
	 * it at.
	 *
	 * <p>
	 * The date is compared rather than assuming the first entry is the day asked
	 * for: a request for a single date sometimes comes back with only a
	 * {@code meta} block, and then there is no entry to compare at all, which is
	 * different from an entry for a different day.
	 */
	private static TimeSeriesResponse.Value valueFor(TimeSeriesResponse response, LocalDate date) {
		if (response.values() == null || response.values().isEmpty()) {
			return null;
		}
		return response.values().stream().filter(value -> value != null && value.datetime() != null)
				.filter(value -> value.datetime().equals(date)).findFirst()
				.orElseGet(() -> (response.values().size() == 1) ? response.values().getFirst() : null);
	}

	/**
	 * The currencies this provider pairs with the ledger currency.
	 *
	 * <p>
	 * <strong>There is no endpoint for this.</strong> The obvious
	 * {@code GET /currency} answers 404 — it does not exist, not even as a paid
	 * tier.
	 *
	 * <p>
	 * What exists is
	 * {@code GET /symbol_search?symbol=LEDGER&type=Physical Currency}, which
	 * answers the pairs the provider quotes. The bases of those pairs are the
	 * currencies it can be asked about, so they are what gets returned — derived
	 * from what the provider actually answers rather than from a list this class
	 * would have to maintain itself.
	 *
	 * <p>
	 * The result is <strong>not the whole ISO 4217</strong> and does not pretend to
	 * be: there is no pagination, the answer is capped around 120 rows, and a
	 * currency the provider does not quote against the ledger is absent. That is
	 * the honest shape of the source, and it is why the lookup is a set the service
	 * grows rather than a table the migration carries — a code missing from here is
	 * still accepted once something adds it.
	 */
	@Override
	public List<PublishedCurrency> publishedCurrencies() {
		String ledger = this.ledgerPolicy.currencyCode();
		SymbolSearchResponse response;
		try {
			response = this.restClient.get()
					.uri(uriBuilder -> uriBuilder.path("/symbol_search").queryParam("symbol", ledger)
							.queryParam("type", "Physical Currency").queryParam("outputsize", SEARCH_PAGE)
							.queryParam("apikey", apiKey()).build())
					.retrieve().body(SymbolSearchResponse.class);
		} catch (RestClientResponseException ex) {
			log.warn("Could not list the pairs of {}: {} ({} {})", source(), SafeLog.failure(ex),
					ex.getStatusCode().value(), SafeLog.scrub(ex.getResponseBodyAsString()));
			return List.of();
		}

		if (response == null || response.data() == null || response.data().isEmpty()) {
			log.warn("{} answered the currency search with no data; the lookup keeps what it has", source());
			return List.of();
		}
		return response.data().stream().map(SymbolSearchResponse.Symbol::symbol)
				.filter(symbol -> symbol != null && symbol.matches(BASE_QUOTE)).map(symbol -> symbol.split("/")[1])
				.filter(other -> !other.equals(ledger)).distinct().map(other -> new PublishedCurrency(other, null))
				.toList();
	}

	private static ClientHttpRequestFactory requestFactory(AppProperties.Rates rates) {
		var factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(rates.connectTimeout());
		factory.setReadTimeout(rates.readTimeout());
		return factory;
	}

	/**
	 * Shape of the {@code /symbol_search} response, mapped to the only field that
	 * matters here.
	 *
	 * <p>
	 * The endpoint answers equities, ETFs, indices and crypto alongside currency
	 * pairs, with a dozen fields of which none is read: the pair's base is the
	 * whole answer, and pulling the rest in would put a second unmapped copy of the
	 * instrument on the record.
	 *
	 */
	record SymbolSearchResponse(List<Symbol> data) {

		record Symbol(String symbol) {
		}

	}

	/**
	 * Shape of the {@code /time_series} response.
	 *
	 * <p>
	 * Only the fields the specification needs are mapped. The endpoint answers more
	 * than this, and letting the rest in would put a second, unmapped version of
	 * the same rate on the record.
	 */
	record TimeSeriesResponse(List<Value> values) {

		record Value(LocalDate datetime, BigDecimal close, @JsonProperty("previous_close") BigDecimal previousClose) {
		}

	}

}
