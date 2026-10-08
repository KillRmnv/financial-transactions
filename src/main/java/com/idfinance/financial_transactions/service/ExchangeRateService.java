package com.idfinance.financial_transactions.service;

import com.idfinance.financial_transactions.config.LedgerPolicy;
import com.idfinance.financial_transactions.domain.ExchangeRate;
import com.idfinance.financial_transactions.entity.Currency;
import com.idfinance.financial_transactions.entity.CurrencyRate;
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
 * not the default path.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExchangeRateService {

	private final CurrencyRateRepository currencyRateRepository;

	private final RatesProvider ratesProvider;

	private final LedgerPolicy ledgerPolicy;

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
			return new Conversion(this.ledgerPolicy.round(amount), BigDecimal.ONE, rateDate);
		}

		Optional<ExchangeRate> stored = findStored(currency, rateDate).or(() -> fetchAndStore(currency, rateDate));
		if (stored.isEmpty()) {
			return Conversion.unavailable();
		}

		ExchangeRate rate = stored.orElseThrow();
		BigDecimal effective = rate.effectiveRate();
		log.debug("Converting {} {} at {} (rate {} from {})", amount, currency.getCode(), effective, rate.rateDate(),
				this.ratesProvider.name());
		// Multiply first, round once: rounding the rate would destroy the only
		// precision
		// the conversion has.
		return new Conversion(this.ledgerPolicy.round(amount.multiply(effective)), effective, rate.rateDate());
	}

	/**
	 * Order of lookup, cheapest first:
	 * <ol>
	 * <li>the rate of the exact day, using {@code previous_close} when the day has
	 * no close;</li>
	 * <li>the most recent rate not later than that day, which covers a day whose
	 * close has not been published yet — without it, a transaction from Monday
	 * arriving on Tuesday would be stuck;</li>
	 * <li>a single external call for the pair.</li>
	 * </ol>
	 */
	private Optional<ExchangeRate> findStored(Currency currency, LocalDate rateDate) {
		return Stream
				.of(this.currencyRateRepository.findForDay(currency, rateDate),
						this.currencyRateRepository.findLatestOnOrBefore(currency, rateDate))
				.flatMap(Optional::stream).map(rate -> ExchangeRate.from(rate, this.ledgerPolicy.currencyCode()))
				.filter(ExchangeRate::effectiveRateIsPositive).findFirst();
	}

	private Optional<ExchangeRate> fetchAndStore(Currency currency, LocalDate rateDate) {
		String ledger = this.ledgerPolicy.currencyCode();
		log.debug("No stored rate for {} on {}; asking {}", currency.getCode(), rateDate, this.ratesProvider.name());
		try {
			ExchangeRate fetched = this.ratesProvider.rateFor(currency.getCode(), ledger, rateDate);
			if (fetched == null) {
				log.warn("The provider has no rate for {}/{} on {}", currency.getCode(), ledger, rateDate);
				return Optional.empty();
			}
			store(currency, fetched);
			return Optional.of(fetched);
		} catch (RuntimeException ex) {
			// The provider is down or slow. The transaction is still accepted, just without
			// a flag, and the
			// pending rows are recalculated later. Swallowing it here rather than
			// propagating is
			// the decision that keeps ingestion lossless.
			log.warn("Could not obtain the rate for {}/{} on {}: {}", currency.getCode(), ledger, rateDate,
					ex.getMessage());
			return Optional.empty();
		}
	}

	@Transactional
	public CurrencyRate store(Currency currency, ExchangeRate rate) {
		String provider = this.ratesProvider.name();
		return this.currencyRateRepository.findForDay(currency, rate.rateDate()).map(existing -> {
			existing.refresh(rate.close(), rate.previousClose());
			log.debug("Refreshed the {} rate for {}: {}", currency.getCode(), rate.rateDate(), rate.effectiveRate());
			return existing;
		}).orElseGet(() -> {
			CurrencyRate stored = this.currencyRateRepository
					.save(new CurrencyRate(currency, rate.rateDate(), rate.close(), rate.previousClose(), provider));
			log.info("Stored a new {} rate for {}: {} (from {})", currency.getCode(), rate.rateDate(),
					rate.effectiveRate(), provider);
			return stored;
		});
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
