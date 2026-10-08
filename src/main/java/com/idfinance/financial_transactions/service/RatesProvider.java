package com.idfinance.financial_transactions.service;

import com.idfinance.financial_transactions.domain.ExchangeRate;
import java.time.LocalDate;

/**
 * The contract every exchange rate provider implements, whatever API it speaks.
 *
 * <p>
 * Separate from any HTTP adapter on purpose: the service layer depends on this
 * and nothing else, so adding a second provider is a new class rather than a
 * new branch in the service.
 *
 * <p>
 * A provider also answers one question about itself, {@link #name()}, because
 * the value ends up in {@code currency_rate.source} and a stored rate has to be
 * traceable to the provider that supplied it. That is also why there is no
 * single hardcoded source string in the adapters: the name belongs to the
 * implementation, and a second provider would otherwise be recorded as the
 * first.
 */
public interface RatesProvider {

	/**
	 * @return the rate for the pair and day, or null when the provider has no data
	 *         for it
	 * @throws RuntimeException
	 *             on a transport or provider failure, after exhausting retries
	 */
	ExchangeRate rateFor(String baseCurrency, String quoteCurrency, LocalDate date);

	/**
	 * @return a short stable identifier of this provider, stored alongside every
	 *         rate it supplied
	 */
	String name();

	/**
	 * @return the currencies the provider publishes, used by the weekly sweep of
	 *         the currency lookup
	 */
	default java.util.List<PublishedCurrency> publishedCurrencies() {
		return java.util.List.of();
	}

	/**
	 * A currency the provider knows about.
	 *
	 * @param code
	 *            ISO 4217 code in whatever case the provider uses
	 * @param displayName
	 *            human readable name
	 */
	record PublishedCurrency(String code, String displayName) {
	}

}
