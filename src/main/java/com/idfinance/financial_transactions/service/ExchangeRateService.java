package com.idfinance.financial_transactions.service;

import com.idfinance.financial_transactions.cache.RateCache;
import com.idfinance.financial_transactions.config.LedgerPolicy;
import com.idfinance.financial_transactions.domain.ExchangeRate;
import com.idfinance.financial_transactions.entity.Currency;
import com.idfinance.financial_transactions.exception.AmountTooLargeException;
import com.idfinance.financial_transactions.metrics.MetricsRecorder;
import com.idfinance.financial_transactions.repository.CurrencyRateRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the rate that converts an amount into the ledger currency,
 * preferring what is already stored.
 *
 * <p>
 * The specification asks for external data to be paid for and slow, so the
 * local table is always consulted first and the external call is a fallback,
 * not the default path. The metrics distinguish the two, because how often the
 * service pays for a rate it already had is the number that decides whether the
 * daily sweep is configured generously enough.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExchangeRateService {

	/**
	 * The transaction is already in the ledger currency, so no rate was looked up
	 * at all.
	 */
	private static final String SOURCE_IDENTITY = "identity";

	private static final String SOURCE_CACHE = "cache";

	private static final String SOURCE_DATABASE = "database";

	private static final String SOURCE_EXTERNAL = "external";

	private final CurrencyRateRepository currencyRateRepository;

	private final RatesProvider ratesProvider;

	private final LedgerPolicy ledgerPolicy;

	private final MetricsRecorder metricsRecorder;

	private final RateCache rateCache;

	private final RateFetchCoordinator rateFetchCoordinator;

	/**
	 * Converts an amount into the ledger currency for the day the transaction
	 * happened, not the day it arrived.
	 *
	 * @return the converted amount and the rate used, or an unavailable conversion
	 *         when no rate exists anywhere; the caller then keeps the transaction
	 *         without a flag rather than inventing one
	 */
	@Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
	public Conversion convert(BigDecimal amount, Currency currency, LocalDate rateDate) {
		if (this.ledgerPolicy.isLedgerCurrency(currency.getCode())) {
			this.metricsRecorder.rateResolved(SOURCE_IDENTITY, this.ledgerPolicy.currencyCode());
			// No clock attached: a transaction in the ledger currency needs no rate, and
			// `null` says exactly that. An earlier version returned `Instant.now()`, which
			// read as "the rate for today" and corrupted the month attribution for
			// transactions
			// dated before today. The DTO field is therefore `rateDate`.
			return new Conversion(amount, BigDecimal.ONE, rateDate);
		}

		Optional<ExchangeRate> stored = findCached(currency, rateDate);
		String source = SOURCE_CACHE;
		if (stored.isEmpty()) {
			stored = findStored(currency, rateDate);
			source = SOURCE_DATABASE;
		}
		if (stored.isEmpty()) {
			stored = fetchAndStore(currency, rateDate);
			source = SOURCE_EXTERNAL;
		}
		if (stored.isEmpty()) {
			return Conversion.unavailable();
		}

		ExchangeRate rate = stored.orElseThrow();
		BigDecimal effective = rate.effectiveRate();
		this.metricsRecorder.rateResolved(source, this.ratesProvider.name());
		log.debug("Converting {} {} at {} (rate {} from {})", amount, currency.getCode(), effective, rate.rateDate(),
				this.ratesProvider.name());
		return converted(amount, effective, rate.rateDate());
	}

	/**
	 * Applies the rate and refuses a product the column cannot hold.
	 *
	 * <p>
	 * The sum is bounded on input, but this is where the bound stops being enough:
	 * the rate is not below one for every currency, so the product of a sum at the
	 * ceiling can exceed what the column stores. Catching it here names the field
	 * and answers 400; on the insert it would arrive as an unnamed constraint.
	 */
	private Conversion converted(BigDecimal amount, BigDecimal effective, LocalDate rateDate) {
		BigDecimal sumInLedger = amount.multiply(effective);
		if (this.ledgerPolicy.exceedsStorageCeiling(sumInLedger)) {
			log.warn("A sum of {} at rate {} converts to {}, which the ledger cannot store", amount, effective,
					sumInLedger);
			throw new AmountTooLargeException(amount, sumInLedger);
		}
		return new Conversion(sumInLedger, effective, rateDate);
	}

	/**
	 * Order of lookup, cheapest first:
	 * <ol>
	 * <li>Redis, which holds what a previous lookup already resolved;</li>
	 * <li>the rate of the exact day, using {@code previous_close} when the day has
	 * no close;</li>
	 * <li>the most recent rate not later than that day, which covers a day whose
	 * close has not been published yet — without it, a transaction from Monday
	 * arriving on Tuesday would be stuck;</li>
	 * <li>a single external call for the pair.</li>
	 * </ol>
	 *
	 * <p>
	 * The cache sits in front of the database rather than replacing it. It is
	 * skipped silently when it is disabled or unreachable, so a Redis outage costs
	 * the round trip it was meant to save and nothing else.
	 */
	private Optional<ExchangeRate> findCached(Currency currency, LocalDate rateDate) {
		String ledger = this.ledgerPolicy.currencyCode();
		Optional<ExchangeRate> cached = this.rateCache.get(currency.getCode(), ledger, rateDate);
		cached.ifPresent(rate -> log.debug("The cache holds the {} rate for {}", currency.getCode(), rateDate));
		return cached;
	}

	private Optional<ExchangeRate> findStored(Currency currency, LocalDate rateDate) {
		Optional<ExchangeRate> found = Stream
				.of(this.currencyRateRepository.findForDay(currency, rateDate),
						this.currencyRateRepository.findLatestOnOrBefore(currency.getCode(), rateDate))
				.flatMap(Optional::stream).map(rate -> ExchangeRate.from(rate, this.ledgerPolicy.currencyCode()))
				.filter(ExchangeRate::effectiveRateIsPositive).findFirst();
		found.ifPresent(
				rate -> this.rateCache.put(currency.getCode(), this.ledgerPolicy.currencyCode(), rateDate, rate));
		return found;
	}

	private Optional<ExchangeRate> fetchAndStore(Currency currency, LocalDate rateDate) {
		String ledger = this.ledgerPolicy.currencyCode();
		log.debug("No stored rate for {} on {}; asking {}", currency.getCode(), rateDate, this.ratesProvider.name());
		try {
			ExchangeRate fetched = this.rateFetchCoordinator
					.rateFor(currency.getCode(), ledger, rateDate,
							() -> Optional.ofNullable(this.ratesProvider.rateFor(currency.getCode(), ledger, rateDate)))
					.orElse(null);
			if (fetched == null) {
				log.warn("The provider has no rate for {}/{} on {}", currency.getCode(), ledger, rateDate);
				return Optional.empty();
			}
			store(currency, fetched);
			return Optional.of(fetched);
		} catch (RuntimeException ex) {
			log.warn("Could not obtain the rate for {}/{} on {}: {}", currency.getCode(), ledger, rateDate,
					ex.getMessage());
			return Optional.empty();
		}
	}

	/**
	 * Writes the rate, or refreshes the row that is already there.
	 *
	 * <p>
	 * One native statement rather than a find-then-write, because two transactions
	 * can legitimately reach here for the same pair and day: a batch resolves its
	 * rates in parallel and three KZT transactions share one provider call through
	 * {@link RateFetchCoordinator}, then all three store what they were handed. A
	 * find-then-write has the first insert the row and the other two fail on
	 * {@code ux_currency_rate_base_date}, and the failure arrives from the commit
	 * as {@code UnexpectedRollbackException} rather than as the constraint
	 * violation that actually happened.
	 */
	@Transactional
	public void store(Currency currency, ExchangeRate rate) {
		String provider = rate.source();
		boolean inserted = this.currencyRateRepository.upsert(currency.getCode(), rate.rateDate(), rate.close(),
				rate.previousClose(), provider) > 0;
		log.debug("{} the {} rate for {}: {}", inserted ? "Stored" : "Refreshed", currency.getCode(), rate.rateDate(),
				rate.effectiveRate());

		this.rateCache.invalidatePair(currency.getCode(), this.ledgerPolicy.currencyCode());
	}

	/**
	 * @param sumInLedger
	 *            the converted amount, or null when no rate was available
	 * @param rate
	 *            the rate used, in ledger units per one unit of the transaction
	 *            currency
	 * @param rateDate
	 *            the day that rate describes, which may precede the transaction
	 *            date
	 */
	public record Conversion(BigDecimal sumInLedger, BigDecimal rate, LocalDate rateDate) {
		public static Conversion unavailable() {
			return new Conversion(null, null, null);
		}

		public boolean isAvailable() {
			return this.sumInLedger != null;
		}

	}
}
