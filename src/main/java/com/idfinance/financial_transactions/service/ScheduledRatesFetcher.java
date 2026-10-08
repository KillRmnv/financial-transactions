package com.idfinance.financial_transactions.service;

import com.idfinance.financial_transactions.config.LedgerPolicy;
import com.idfinance.financial_transactions.domain.ExchangeRate;
import com.idfinance.financial_transactions.entity.Currency;
import com.idfinance.financial_transactions.entity.CurrencyPair;
import com.idfinance.financial_transactions.repository.CurrencyPairRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refreshes the rates of the currencies marked active in {@code currency_pair}.
 *
 * <p>
 * The set comes from the database rather than from configuration, so an
 * operator can add or retire one without a release. Rates for currencies
 * outside that set are fetched on demand by {@link ExchangeRateService},
 * because each external call costs money and sweeping currencies nobody
 * transacts in would waste that budget.
 *
 * <p>
 * The quote is not read from anywhere and never compared with anything:
 * {@link LedgerPolicy} supplies it and the table does not store a second copy.
 * Storing one would have created a second source of truth that only a
 * code-level check could reconcile.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ScheduledRatesFetcher {

	private final CurrencyPairRepository currencyPairRepository;

	private final RatesProvider ratesProvider;

	private final ExchangeRateService exchangeRateService;

	private final LedgerPolicy ledgerPolicy;

	private final Clock clock;

	@Scheduled(cron = "0 0 ${app.scheduled-fetch-hour} * * *", zone = "${app.month-timezone}")
	@Transactional
	public void refreshDaily() {
		List<CurrencyPair> tracked = this.currencyPairRepository.findByActiveTrueOrderByBaseCurrencyCodeAsc();
		LocalDate today = LocalDate.now(this.clock);
		log.info("Refreshing {} tracked currencies against {} for {} from {}", tracked.size(),
				this.ledgerPolicy.currencyCode(), today, this.ratesProvider.name());

		for (CurrencyPair pair : tracked) {
			fetchAndStore(pair.getBaseCurrency(), today);
		}
	}

	private void fetchAndStore(Currency base, LocalDate date) {
		String quote = this.ledgerPolicy.currencyCode();
		try {
			ExchangeRate rate = this.ratesProvider.rateFor(base.getCode(), quote, date);
			if (rate == null) {
				log.info("No {} published for {} on {}", quote, base.getCode(), date);
				return;
			}
			this.exchangeRateService.store(base, rate);
		} catch (RuntimeException ex) {
			// One unavailable currency must not abort the whole refresh, and a failed
			// refresh must never
			// lose stored rates.
			log.warn("Could not refresh {}/{} on {}: {}", base.getCode(), quote, date, ex.getMessage());
		}
	}

}
