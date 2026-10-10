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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 * <li>{@code GET /v2/currencies} — the list, filtered to codes that have not
 * been retired
 * </ul>
 *
 * <p>
 * Two things make it worth having alongside the paid provider rather than
 * instead of it. It needs no key and has no quotas at all — no daily cap, no
 * monthly cap — so it is both free and not worth paying to avoid. And it
 * answers a rate for <em>every</em> calendar day, weekends and public holidays
 * included: {@code close} for a Saturday came back as a real number, not a
 * null. That is why {@code previousClose} is always null here. The fallback in
 * {@link ExchangeRate#effectiveRate()} exists for a provider that has the gap,
 * and this one does not.
 *
 * <p>
 * History reaches back to 1948, which also means the free tier of the paid
 * provider — one year back — cannot be the only source for a transaction dated
 * further back than that.
 *
 * <p>
 * {@code publishedCurrencies} filters on {@code end_date}. The unfiltered list
 * carries about eighty retired codes — {@code DEM}, {@code FRF}, {@code TRL},
 * {@code RUR} — and importing them would add currencies to the lookup that no
 * longer exist.
 */
@Component
@ConditionalOnProperty(name = "app.rates.frankfurter.base-url", matchIfMissing = true)
@Slf4j
public final class FrankfurterRatesProvider implements RatesProvider {

	/**
	 * What lands in {@code currency_rate.source} for a rate that came from here. A
	 * named constant because the composite reports it when this provider is the
	 * only one configured, and a string literal in two places would be two things
	 * to keep in step.
	 */
	public static final String NAME = "frankfurter";

	private final AppProperties properties;

	private final RestClient client;

	public FrankfurterRatesProvider(AppProperties properties) {
		this.properties = properties;
		// Built here rather than from the injected builder because the base URL is this
		// provider's own configuration, and a shared client would carry whichever URL
		// was
		// configured first.
		this.client = RestClient.builder().baseUrl(properties.rates().frankfurter().baseUrl())
				.requestFactory(requestFactory(properties.rates())).build();
	}

	private static ClientHttpRequestFactory requestFactory(AppProperties.Rates rates) {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(rates.connectTimeout());
		factory.setReadTimeout(rates.readTimeout());
		return factory;
	}

	@Override
	public String name() {
		return NAME;
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

		return Arrays.stream(rows).filter(row -> row.isCurrent())
				.map(row -> new RatesProvider.PublishedCurrency(row.isoCode(), row.displayName())).toList();
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
	private record Currency(@JsonProperty("iso_code") String isoCode, @JsonProperty("name") String displayName,
			@JsonProperty("end_date") String endDate) {

		boolean isCurrent() {
			return this.endDate == null || this.endDate.isBlank();
		}

	}

}
