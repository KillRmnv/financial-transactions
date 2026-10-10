package com.idfinance.financial_transactions.client;

import com.idfinance.financial_transactions.domain.ExchangeRate;
import com.idfinance.financial_transactions.service.RatesProvider;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * The paid provider, and Frankfurter when the paid one has nothing to say.
 *
 * <p>
 * Conditional on at least one of the two URLs being configured, which is what
 * keeps it out of the way in the suites that stub {@code RatesProvider}
 * in-process. A composite over a stub has nothing to fall back to, and marking
 * both it and the stub as {@code @Primary} is a context that refuses to start.
 * The stub contexts therefore set both URLs empty rather than this bean being
 * conditionally absent by some subtler means.
 *
 * <p>
 * The two are asked in order and the first answer wins, whatever kind it was: a
 * rate, or an absence of one. Falling through on an absence is the point.
 * Twelve Data's free tier reaches back a year and leaves {@code previous_close}
 * empty on weekends, so a transaction older than that, or one dated on a
 * Saturday, has no answer from it — and an ingestion path that stops there
 * stores a transaction it could have priced.
 *
 * <p>
 * The provider that answered is carried on the rate itself
 * ({@link ExchangeRate#source()}) rather than being asked here at write time,
 * because that is the only way {@code currency_rate.source} can name the one
 * that actually supplied the number.
 *
 * <p>
 * Dependencies are the concrete types rather than a
 * {@code List<RatesProvider>}. This class implements the interface it would
 * otherwise appear in, and a list injection would hand it itself.
 *
 * <p>
 * A transport failure does not fall through. The caller already treats that as
 * "store it without a flag", and retrying it against a second provider would
 * turn one outage into two requests and then the same answer.
 */
@Component
@ConditionalOnExpression("'${app.rates.base-url:}' != '' or '${app.rates.frankfurter.base-url:}' != ''")
@Primary
@RequiredArgsConstructor
@Slf4j
public final class FallbackRatesProvider implements RatesProvider {

	private final ObjectProvider<TwelveDataRatesProvider> paid;

	private final ObjectProvider<FrankfurterRatesProvider> free;

	@Override
	public String name() {
		return primaryName();
	}

	@Override
	public ExchangeRate rateFor(String baseCurrency, String quoteCurrency, LocalDate date) {
		for (RatesProvider provider : providers()) {
			try {
				ExchangeRate rate = provider.rateFor(baseCurrency, quoteCurrency, date);
				if (rate != null) {
					if (!provider.name().equals(primaryName())) {
						log.debug("{} had no {} for {}, answered by {}", primaryName(),
								baseCurrency + "/" + quoteCurrency, date, provider.name());
					}
					return rate;
				}
			} catch (RuntimeException ex) {
				// Re-thrown rather than swallowed: this is a transport failure, not an absence,
				// and the caller's handling of it is its own decision to make.
				throw ex;
			}
		}

		log.warn("No provider has a rate for {} on {}", baseCurrency + "/" + quoteCurrency, date);
		return null;
	}

	/**
	 * The first provider that publishes a list, rather than a merge of all of them.
	 *
	 * <p>
	 * A union would be wrong in the one direction that matters: Frankfurter carries
	 * about eighty retired codes behind a filter, and the paid provider's list is
	 * narrower. Taking one provider's view keeps the lookup a statement about a
	 * single source.
	 */
	@Override
	public List<RatesProvider.PublishedCurrency> publishedCurrencies() {
		for (RatesProvider provider : providers()) {
			List<RatesProvider.PublishedCurrency> published = provider.publishedCurrencies();
			if (!published.isEmpty()) {
				return published;
			}
		}
		return List.of();
	}

	private List<RatesProvider> providers() {
		return java.util.stream.Stream.of(this.paid.getIfAvailable(), this.free.getIfAvailable())
				.filter(java.util.Objects::nonNull).toList();
	}

	/**
	 * The name of whichever provider is asked first, which is the one a rate is
	 * recorded under when it is the primary that answered.
	 *
	 * <p>
	 * {@code getIfAvailable()} called once and held, rather than tested against
	 * null and then called again: the two calls are separate lookups, and SpotBugs
	 * is right that the second one is dereferenced on a value nobody checked.
	 */
	private String primaryName() {
		RatesProvider paid = this.paid.getIfAvailable();
		return (paid != null) ? paid.name() : FrankfurterRatesProvider.NAME;
	}

}
