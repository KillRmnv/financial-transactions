package com.idfinance.financial_transactions.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.idfinance.financial_transactions.config.AppProperties;
import com.idfinance.financial_transactions.config.LedgerPolicy;
import com.idfinance.financial_transactions.domain.ExchangeRate;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

	private final LedgerPolicy ledgerPolicy;

	private final Retry retry;

	/**
	 * How many rows to ask for. The provider caps this well below any useful page
	 * size and offers no pagination, so the number is a ceiling rather than a
	 * choice.
	 */
	private static final String SEARCH_PAGE = "500";

	/** A currency pair as the provider spells it, and nothing else. */
	private static final String BASE_QUOTE = "^[A-Z]{3}/[A-Z]{3}$";

	public TwelveDataRatesProvider(AppProperties properties, LedgerPolicy ledgerPolicy) {
		this.properties = properties;
		this.ledgerPolicy = ledgerPolicy;
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
		return this.properties.rates().source();
	}

	/**
	 * The rate as published, and the inverse if the provider does not publish this
	 * orientation.
	 *
	 * <p>
	 * Twelve Data has no {@code KZT/USD} — the symbol does not exist and answers
	 * 404 with "symbol or figi parameter is missing or invalid", which reads like a
	 * malformed request rather than a missing pair. {@code USD/KZT} is there, so a
	 * provider that answers only what it was asked produces no tenge rate at all
	 * and the transaction is stored unflagged. Asking for the other side and
	 * inverting is what makes the specification's own pair work against this
	 * provider.
	 *
	 * <p>
	 * The inverse costs one more request only when the direct one fails, which is
	 * the uncommon case: {@code RUB/USD}, {@code EUR/USD} and most others are
	 * published the way they are asked for.
	 */
	@Override
	public ExchangeRate rateFor(String baseCurrency, String quoteCurrency, LocalDate date) {
		Optional<ExchangeRate> direct = askFor(baseCurrency + "/" + quoteCurrency, baseCurrency, quoteCurrency, date);
		if (direct.isPresent()) {
			return direct.get();
		}

		log.info("{} publishes no {}/{}, asking for the pair the other way round", this.properties.rates().source(),
				baseCurrency, quoteCurrency);
		return askFor(quoteCurrency + "/" + baseCurrency, quoteCurrency, baseCurrency, date).map(ExchangeRate::inverted)
				.orElse(null);
	}

	/**
	 * One attempt at one symbol, with the retry policy around it.
	 *
	 * <p>
	 * Wrapped in an {@link Optional} rather than returned as a bare reference: the
	 * supplier answers null whenever the provider does not publish the symbol, and
	 * {@code Retry.executeSupplier} carries {@code @NonNull}, which is wrong about
	 * a {@link java.util.function.Supplier} and which SpotBugs takes at its word —
	 * the check then reads as redundant and is removed, and the first missing pair
	 * turns into an NPE. The Optional is the type saying what is true: nothing,
	 * rather than nothing unless you look.
	 */
	private Optional<ExchangeRate> askFor(String symbol, String baseCurrency, String quoteCurrency, LocalDate date) {
		return Optional.ofNullable(this.retry.executeSupplier(() -> {
			log.debug("Asking {} for {}/{}", this.properties.rates().source(), symbol, date);
			return fetchOnce(symbol, baseCurrency, quoteCurrency, date);
		}));
	}

	/**
	 * The close of that day, and the last close before it as the fallback.
	 *
	 * <p>
	 * <strong>The provider does not send {@code previous_close}.</strong> Its
	 * answer carries exactly {@code datetime, open, high, low, close} — checked
	 * against the live API, not assumed. Reading that field, which this record was
	 * written to do, therefore produces null on every request, and the rule the
	 * specification states for п.3 — no close for the day, a weekend or a holiday,
	 * so use the previous close — never runs. It fails quietly: the transaction is
	 * stored without a flag, which is the right answer to a missing rate and the
	 * wrong one to a rate that exists.
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

		// Either shape of "no rate for that day" leads to the same place: the provider
		// omits a
		// closed day from the series entirely, so an empty answer is the holiday case
		// rather
		// than a failure, and a present entry with a null close is the same thing from
		// another
		// API version. Deciding between them here would mean deciding which one to
		// believe.
		TimeSeriesResponse.Value value = valueFor(ofTheDay, date);
		if (value != null && value.close() != null) {
			// A previous_close is kept when a deployment of the API does send one: it is
			// the
			// provider's own answer rather than one reconstructed from a window.
			return new ExchangeRate(baseCurrency, quoteCurrency, date, value.close(), value.previousClose(), name());
		}

		BigDecimal previous = lastCloseBefore(symbol, date);
		if (previous == null) {
			return null;
		}
		log.debug("{} has no close for {} on {}, falling back to {}", this.properties.rates().source(), symbol, date,
				previous);
		return new ExchangeRate(baseCurrency, quoteCurrency, date, null, previous, name());
	}

	/**
	 * How far back the fallback request looks.
	 *
	 * <p>
	 * Ten days is a calendar fortnight, which covers the longest run of closed days
	 * a currency market has — a holiday weekend plus a day either side — without a
	 * window wide enough for the answer to reach past a gap the specification would
	 * rather see reported as missing.
	 */
	private static final int FALLBACK_WINDOW_DAYS = 10;

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
		LocalDate from = date.minusDays(FALLBACK_WINDOW_DAYS);
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
							.queryParam("interval", "1day").queryParam("apikey", this.properties.rates().apiKey()))
							.build())
					.retrieve().body(TimeSeriesResponse.class);
		} catch (HttpClientErrorException.NotFound ex) {
			// The symbol does not exist for this provider, which is a fact about the pair
			// rather
			// than a failure of the call: asking again cannot create it, and letting the
			// exception
			// out would spend every retry before the caller could try the other
			// orientation.
			log.debug("{} has no symbol {}: {}", this.properties.rates().source(), symbol,
					ex.getResponseBodyAsString());
			return null;
		} catch (HttpClientErrorException.BadRequest ex) {
			// The provider answers 400 "No data is available on the specified dates" for a
			// date
			// older than the history its plan keeps — a fact about the data, not a
			// malformed
			// request. Let it out and the log reads "400 Bad Request" against our own call,
			// which
			// is both wrong and alarming on a date the specification is full of. Any other
			// 400 is
			// still a defect on this side and still travels.
			if (ex.getResponseBodyAsString().contains(NO_DATA)) {
				log.debug("{} has no data before the date asked for: {}", this.properties.rates().source(),
						ex.getResponseBodyAsString());
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
	 * tier — and the first version of this adapter called it anyway, which made
	 * every startup import fail and the lookup stay at whatever the migration
	 * seeded. A stub cannot catch that: WireMock answers whatever it is given a
	 * stub for, and this path was only ever exercised against a provider that had
	 * been assumed rather than asked.
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
							.queryParam("apikey", this.properties.rates().apiKey()).build())
					.retrieve().body(SymbolSearchResponse.class);
		} catch (RestClientResponseException ex) {
			log.warn("Could not list the pairs of {}: {} ({} {})", this.properties.rates().source(), ex.getMessage(),
					ex.getStatusCode().value(), ex.getResponseBodyAsString());
			return List.of();
		}

		if (response == null || response.data() == null || response.data().isEmpty()) {
			// Said out loud because the alternative is invisible. The free tier answers a
			// request over
			// its quota with an empty body, so "the provider published nothing" and "the
			// provider
			// published nothing because we were not allowed to ask" are the same empty
			// list, and a
			// lookup that stops growing looks exactly like one whose provider has nothing
			// new.
			log.warn("{} answered the currency search with no data; the lookup keeps what it has",
					this.properties.rates().source());
			return List.of();
		}
		return response.data().stream().map(SymbolSearchResponse.Symbol::symbol)
				// Only the LEDGER/OTHER pairs: symbol_search also answers equities, ETFs and
				// crypto,
				// and a code parsed out of "AAPL" would be a currency the provider never
				// quotes.
				.filter(symbol -> symbol != null && symbol.matches(BASE_QUOTE))
				// The OTHER side, not the base. The search is asked about the ledger currency,
				// so
				// every pair it answers is LEDGER/SOMETHING and the base is the same value in
				// every
				// row — taking it yields the ledger itself, filtered out one line below, and an
				// empty import that looks like a provider with nothing new.
				.map(symbol -> symbol.split("/")[1]).filter(other -> !other.equals(ledger)).distinct()
				.map(other -> new PublishedCurrency(other, null)).toList();
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
	 * @param data
	 *            the instruments matching the search, in no order the service
	 *            relies on
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
		record Value(LocalDate datetime, BigDecimal close, @JsonProperty("previous_close") BigDecimal previousClose) {
		}

	}

}
