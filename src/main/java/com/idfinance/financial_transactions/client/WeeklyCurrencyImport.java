package com.idfinance.financial_transactions.client;

import com.idfinance.financial_transactions.entity.ScheduledJob;
import com.idfinance.financial_transactions.service.CurrencyImporter;
import com.idfinance.financial_transactions.service.RatesProvider;
import com.idfinance.financial_transactions.service.ScheduledJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adds currencies that appeared after the seed in the migration, once a week.
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
 * Note what switching it off costs: an ISO 4217 code introduced after this
 * deployment started stays unaccepted until the code is added by hand, because
 * {@code CurrencyBootstrap} only reads what the provider published when the
 * service came up. The specification names only KZT and RUB, and both are
 * seeded, so nothing the assignment asks for is affected.
 *
 * <p>
 * The import itself is {@link CurrencyImporter}, which
 * {@code CurrencyBootstrap} also calls at startup; this class is only the
 * schedule. It was the loop itself until the startup import needed the same
 * one, and living in {@code client} it was in the wrong package for what it
 * does.
 */
@Component
@ConditionalOnBean(RatesProvider.class)
@ConditionalOnProperty(name = "app.scheduling.enabled", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class WeeklyCurrencyImport {

	private final ScheduledJobService scheduledJobService;

	private final CurrencyImporter currencyImporter;

	// The schedule is a property, not the literal the annotation came with: an
	// operator who
	// pays for the endpoint is entitled to decide how often it is called without a
	// release,
	// and one who declines to pay at all switches the row off instead.
	@Scheduled(cron = "${app.scheduling.weekly-currency-import.cron:0 30 4 * * MON}", zone = "${app.month-timezone}")
	@Transactional
	public void refreshCurrencies() {
		if (!this.scheduledJobService.isEnabled(ScheduledJob.Names.WEEKLY_CURRENCY_IMPORT)) {
			log.debug("Skipping the currency import: {} is switched off in scheduled_job",
					ScheduledJob.Names.WEEKLY_CURRENCY_IMPORT);
			return;
		}

		this.currencyImporter.importPublished();
	}

}
