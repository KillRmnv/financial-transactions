package com.idfinance.financial_transactions.client;

import com.idfinance.financial_transactions.entity.Currency;
import com.idfinance.financial_transactions.entity.ScheduledJob;
import com.idfinance.financial_transactions.repository.CurrencyRepository;
import com.idfinance.financial_transactions.service.RatesProvider;
import com.idfinance.financial_transactions.service.ScheduledJobService;
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
 * <strong>The sweep can be switched off on its own</strong>, and without a
 * restart, by setting {@code scheduled_job.enabled} to false for
 * {@link ScheduledJob.Names#WEEKLY_CURRENCY_IMPORT}. That is a row rather than
 * a property on purpose: a bean condition is settled while the context
 * registers it, so a property here would mean a restart to save a call that
 * happens once a year. The row is also shared by every replica, whereas one in
 * memory would leave the other instances still paying. A deployment on a free
 * plan pays for the daily rate sweep, which the specification requires, and can
 * decline to pay for the currency listing, which it does not. The default is
 * on, so a deployment that does nothing keeps today's behaviour.
 *
 * <p>
 * Note what switching it off costs: an ISO 4217 code introduced after the seed
 * stays unaccepted until a migration adds it, because this is the only writer
 * of the lookup apart from the migration. The specification names only KZT and
 * RUB, and both are seeded, so nothing the assignment asks for is affected.
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

	private final ScheduledJobService scheduledJobService;

	@Scheduled(cron = "0 30 4 * * MON", zone = "${app.month-timezone}")
	@Transactional
	public void refreshCurrencies() {
		// Checked here rather than as a bean condition, because a condition is settled
		// while the
		// context registers it and cannot be changed afterwards. The only thing a
		// disabled job does
		// is return, and a JVM waking up once a week costs nothing — it is the provider
		// call that
		// costs credits, and this is where that call stops.
		if (!this.scheduledJobService.isEnabled(ScheduledJob.Names.WEEKLY_CURRENCY_IMPORT)) {
			log.debug("Skipping the currency import: {} is switched off in scheduled_job",
					ScheduledJob.Names.WEEKLY_CURRENCY_IMPORT);
			return;
		}

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
