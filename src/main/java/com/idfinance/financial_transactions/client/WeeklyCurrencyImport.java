package com.idfinance.financial_transactions.client;

import com.idfinance.financial_transactions.entity.Currency;
import com.idfinance.financial_transactions.repository.CurrencyRepository;
import com.idfinance.financial_transactions.service.RatesProvider;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adds currencies that appeared after the seed in the migration.
 *
 * <p>
 * Weekly rather than daily: new ISO 4217 codes are rare, and a daily sweep of a
 * paid endpoint would spend budget to learn nothing most mornings. A
 * transaction in an unknown code is refused with a clear 404 until the next
 * sweep, which is the intended behaviour — an unknown currency is a contract
 * question, not something to guess at.
 *
 * <p>
 * Only active when a provider is configured; with {@link NoRatesProvider} in
 * place there is nothing to sweep, and the seeded list stands on its own.
 */
@Component
@ConditionalOnBean(RatesProvider.class)
@ConditionalOnProperty(name = "app.scheduling.enabled", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class WeeklyCurrencyImport {

	private final RatesProvider ratesProvider;

	private final CurrencyRepository currencyRepository;

	@Scheduled(cron = "0 30 4 * * MON", zone = "${app.month-timezone}")
	@Transactional
	public void refreshCurrencies() {
		List<RatesProvider.PublishedCurrency> published;
		try {
			published = this.ratesProvider.publishedCurrencies();
		} catch (RuntimeException ex) {
			log.warn("Could not list the currencies published by {}: {}", this.ratesProvider.name(), ex.getMessage());
			return;
		}

		int added = 0;
		for (RatesProvider.PublishedCurrency entry : published) {
			String code = normalize(entry.code());
			if (code == null) {
				continue;
			}
			if (this.currencyRepository.existsByCode(code)) {
				continue;
			}
			this.currencyRepository.save(new Currency(code, entry.displayName()));
			added++;
			log.info("Added the currency {} from {}", code, this.ratesProvider.name());
		}
		log.info("Currency sweep finished, {} new codes from {}", added, this.ratesProvider.name());
	}

	/**
	 * @return the uppercase code, or null when the entry is not a usable ISO 4217
	 *         code
	 */
	private static String normalize(String rawCode) {
		if (rawCode == null) {
			return null;
		}
		String code = rawCode.trim().toUpperCase(Locale.ROOT);
		return code.matches("^[A-Z]{3}$") ? code : null;
	}

}
