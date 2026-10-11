package com.idfinance.financial_transactions.service;

import com.idfinance.financial_transactions.logging.SafeLog;
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
 * {@code WeeklyCurrencyImport} on a schedule.
 *
 * <p>
 * <strong>It never removes anything.</strong> A code that has been seen once
 * stays: transactions already stored reference it, and a currency going out of
 * circulation does not make the transactions that used it invalid. Retiring
 * belongs to {@code currency_pair}, which says what is swept, not to the
 * lookup, which says what is accepted.
 *
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
			log.warn("Could not list the currencies published by {}: {}", this.ratesProvider.name(),
					SafeLog.failure(ex));
			return 0;
		}

		int added = 0;
		for (RatesProvider.PublishedCurrency entry : published) {
			String code = normalize(entry.code());
			if (code == null) {
				continue;
			}
			if (this.currencyRepository.insertIfAbsent(code, displayNameOf(code, entry)) > 0) {
				added++;
				log.info("Added the currency {} from {}", code, this.ratesProvider.name());
			}
		}
		log.info("Currency import finished, {} new codes of {} published by {}", added, published.size(),
				this.ratesProvider.name());
		return added;
	}

	/**
	 * The label to store, which is not always one the provider gave.
	 *
	 * <p>
	 * {@code currency.display_name} is {@code not null}, and a provider is under no
	 * obligation to name what it quotes: Twelve Data answers a currency pair with a
	 * symbol and nothing else, so an import that stored its answer as given would
	 * fail on the constraint for every row it tried to add. The code is used
	 * instead — a column called "the name" holding {@code EUR} is uninformative,
	 * but it is visible and an operator can correct it, whereas a failed insert is
	 * neither.
	 *
	 * <p>
	 * Blank rather than null, because a provider answering {@code ""} or
	 * {@code " "} is making the same omission more quietly.
	 */
	private static String displayNameOf(String code, RatesProvider.PublishedCurrency entry) {
		String name = entry.displayName();
		return (name == null || name.isBlank()) ? code : name.trim();
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
