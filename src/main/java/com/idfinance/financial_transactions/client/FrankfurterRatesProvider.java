package com.idfinance.financial_transactions.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.idfinance.financial_transactions.config.AppProperties;
import com.idfinance.financial_transactions.domain.ExchangeRate;
import com.idfinance.financial_transactions.service.ConditionalOnProviderConfigured;
import com.idfinance.financial_transactions.service.RatesProvider;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Wire adapter for <a href="https://frankfurter.dev">Frankfurter</a>, and for
 * no other provider.
 *
 * <ul>
 * <li>{@code GET /v2/rate/{base}/{quote}?date=YYYY-MM-DD} — answers
 * {@code {"date","base","quote","rate"}}, one pair in the orientation asked for
 * </li>
 * <li>{@code GET /v2/currencies} — the list, which carries only codes the
 * provider still holds data for
 * </ul>
 *
 * <p>
 * Retired codes are absent from that endpoint rather than filtered out of it.
 * Checked against the live one: 165 rows, none of them {@code DEM},
 * {@code FRF}, {@code TRL} or {@code RUR}, which is where the safety comes
 * from.
 */
@Component
@ConditionalOnProviderConfigured("frankfurter")
@Slf4j
public final class FrankfurterRatesProvider implements RatesProvider {

	/**
	 * Which block of {@code app.rates.providers} this adapter reads. The provider
	 * name is the map key, so it appears exactly twice — here and in
	 * {@code application.yml} — and nowhere else in the code.
	 */
	private static final String PROVIDER = "frankfurter";

	private final AppProperties properties;

	private final RestClient client;

	public FrankfurterRatesProvider(AppProperties properties) {
		this.properties = properties;
		this.client = RestClient.builder().baseUrl(settings().baseUrl())
				.requestFactory(requestFactory(properties.rates())).build();
	}

	/** This provider's settings, or a null when its block is absent. */
	private AppProperties.Rates.Provider settings() {
		return this.properties.rates().providers().get(PROVIDER);
	}

	private static ClientHttpRequestFactory requestFactory(AppProperties.Rates rates) {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(rates.connectTimeout());
		factory.setReadTimeout(rates.readTimeout());
		return factory;
	}

	@Override
	public String name() {
		return PROVIDER;
	}

	/**
	 * A single GET, with the pair in the orientation asked for.
	 *
	 * <p>
	 * No inversion dance, unlike the paid provider: this one publishes
	 * {@code USD/KZT} directly, so there is no 404 to retry the other way round and
	 * nothing to divide.
	 *
	 * <p>
	 * A 404 means the provider has no rate for that pair and day, which is the same
	 * answer as a null and not a failure — the caller falls back to the next
	 * source. A 422 means the code is not one it knows, which is also "no rate"
	 * rather than something to retry.
	 */
	@Override
	public ExchangeRate rateFor(String baseCurrency, String quoteCurrency, LocalDate date) {
		String base = normalize(baseCurrency);
		String quote = normalize(quoteCurrency);

		if (base.equals(quote)) {
			return ExchangeRate.identity(quote, date);
		}

		Pair answer = this.fetchWithRetry(base, quote, date);
		if (answer == null) {
			return null;
		}

		// The date the provider reports is the one that answered. Trusting the
		// requested date
		// instead would file a rate under a day it does not describe, and the monthly
		// lookup
		// asks for the first of a month precisely so that a stored rate is found by it.
		return new ExchangeRate(base, quote, answer.date(), answer.rate(), null, name());
	}

	@Override
	public List<RatesProvider.PublishedCurrency> publishedCurrencies() {
		Currency[] rows;
		try {
			rows = this.client.get().uri(uriBuilder -> uriBuilder.path("/v2/currencies").build()).retrieve()
					.body(Currency[].class);
		} catch (RestClientException ex) {
			log.warn("Could not list the currencies published by {}: {}", name(), ex.getMessage());
			return List.of();
		}

		if (rows == null) {
			return List.of();
		}

		// No filter on end_date, and this is the second version of that decision. It
		// was read as "the day the currency was withdrawn" and used to drop anything
		// that carried one, on the theory that the list mixes in retired codes. Checked
		// against the live endpoint: it carries end_date on every one of its 165 rows,
		// with three distinct values, all within days of the request — so the field is
		// the last day the provider holds data for, not a withdrawal, and the filter
		// discarded the entire list. Nothing was imported and the log said zero of
		// zero,
		// which reads like a provider with nothing new rather than like a filter that
		// ate everything.
		//
		// The retired codes are not in the answer to begin with. DEM, FRF, TRL and RUR
		// are absent from the live response, which is where the safety actually came
		// from; the filter was never doing the work it claimed.
		return Arrays.stream(rows).map(row -> new RatesProvider.PublishedCurrency(row.isoCode(), row.displayName()))
				.toList();
	}

	/**
	 * One attempt with the retry policy around it.
	 */
	private Pair fetchWithRetry(String base, String quote, LocalDate date) {
		Retry retry = Retry.of("frankfurter",
				RetryConfig.custom().maxAttempts(this.properties.rates().attempts())
						.intervalFunction(IntervalFunction.of(this.properties.rates().retryBackoff()))
						.retryOnException(failure -> true).build());

		return Retry.decorateSupplier(retry, () -> this.fetchOnce(base, quote, date)).get();
	}

	private Pair fetchOnce(String base, String quote, LocalDate date) {
		log.debug("Asking {} for {}/{}", name(), base, quote, date);
		try {
			return this.client.get().uri(uriBuilder -> uriBuilder.path("/v2/rate/" + base + "/" + quote)
					.queryParam("date", date.toString()).build()).retrieve().body(Pair.class);
		} catch (RestClientResponseException ex) {
			if (ex.getStatusCode().value() == 404 || ex.getStatusCode().value() == 422) {
				log.debug("{} has no {} for {} on {}", name(), base + "/" + quote, date, ex.getStatusCode());
				return null;
			}
			throw ex;
		}
	}

	/**
	 * The provider is documented in lower case and answers either, but a request
	 * built from an uppercase stored code must not be the thing that decides.
	 */
	private static String normalize(String code) {
		return (code != null) ? code.trim().toLowerCase(Locale.ROOT) : "";
	}

	/**
	 * The rate row. {@code rate} is null when the provider knows the pair but has
	 * nothing for that day, which is the one case {@link ExchangeRate} refuses to
	 * accept — so it is treated here as no rate at all.
	 */
	private record Pair(@JsonProperty("date") LocalDate date, @JsonProperty("rate") BigDecimal rate) {
	}

	/**
	 * A row of the currency list.
	 *
	 * <p>
	 * {@code endDate} is absent for a code that is still in use and carries a date
	 * for one that has been retired. Both {@code null} and an empty string mean the
	 * same thing here, because the field is omitted in one response shape and sent
	 * blank in the other.
	 */
	/**
	 * One row of {@code /v2/currencies}.
	 *
	 * <p>
	 * The endpoint also sends {@code start_date}, {@code end_date},
	 * {@code iso_numeric} and {@code symbol}; none of them is read.
	 * {@code end_date} in particular was read, on the belief that it said a
	 * currency had been withdrawn, and is not: it is the last day the provider
	 * holds data for, and it is present on every row.
	 */
	private record Currency(@JsonProperty("iso_code") String isoCode, @JsonProperty("name") String displayName) {

	}

}
