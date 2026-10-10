package com.idfinance.financial_transactions.service;

import com.idfinance.financial_transactions.repository.CurrencyRepository;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fills the currency lookup from the codes the provider publishes.
 *
 * <p>
 * Two callers, one implementation: {@code CurrencyBootstrap} at startup and
 * {@code WeeklyCurrencyImport} on a schedule. They used to be the same loop
 * written once, which is how it stayed in the {@code client} package — where it
 * did not belong, since the {@code client} package is HTTP adapters and this is
 * business logic that happens to call one.
 *
 * <p>
 * <strong>It never removes anything.</strong> A code that has been seen once
 * stays: transactions already stored reference it, and a currency going out of
 * circulation does not make the transactions that used it invalid. Retiring
 * belongs to {@code currency_pair}, which says what is swept, not to the
 * lookup, which says what is accepted.
 *
 * <p>
 * <strong>A provider failure costs nothing but the import.</strong> The lookup
 * keeps whatever it had and a transaction whose currency is missing from it is
 * refused rather than invented, which is the same refusal it got before the
 * call was made.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CurrencyImporter {

	private final RatesProvider ratesProvider;

	private final CurrencyRepository currencyRepository;

	/**
	 * Adds every published code the lookup does not have.
	 *
	 * <p>
	 * Idempotent, and safe to call as often as anything likes: the insert is
	 * {@code on conflict do nothing}, so a second call adds nothing rather than
	 * failing. Both callers rely on that — the startup import and the weekly sweep
	 * can overlap, and several replicas run the startup one at the same moment.
	 *
	 * @return how many codes were added, 0 when the provider published nothing or
	 *         could not be asked
	 */
	@Transactional
	public int importPublished() {
		List<RatesProvider.PublishedCurrency> published;
		try {
			published = this.ratesProvider.publishedCurrencies();
		} catch (RuntimeException ex) {
			// A provider that is down must not stop the service from starting, and must not
			// empty a lookup that already has entries in it. The weekly sweep is the retry.
			log.warn("Could not list the currencies published by {}: {}", this.ratesProvider.name(), ex.getMessage());
			return 0;
		}

		int added = 0;
		for (RatesProvider.PublishedCurrency entry : published) {
			String code = normalize(entry.code());
			if (code == null) {
				continue;
			}
			if (this.currencyRepository.insertIfAbsent(code, entry.displayName()) > 0) {
				added++;
				log.info("Added the currency {} from {}", code, this.ratesProvider.name());
			}
		}
		log.info("Currency import finished, {} new codes of {} published by {}", added, published.size(),
				this.ratesProvider.name());
		return added;
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
