package com.idfinance.financial_transactions.domain;

import com.idfinance.financial_transactions.entity.CurrencyRate;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A daily exchange rate for one pair, together with the rule that picks between
 * {@code close} and {@code previous_close}.
 *
 * <p>
 * The quote side is never hardcoded here. It is whatever the ledger is kept in,
 * passed in by the caller, because the specification fixes it to USD today and
 * nothing about the arithmetic requires that to stay true.
 *
 * @param baseCurrency
 *            currency of the transaction
 * @param quoteCurrency
 *            the ledger currency, from {@code app.ledger.currency-code}
 * @param rateDate
 *            the day this rate describes
 * @param close
 *            closing rate of the day
 * @param previousClose
 *            the last known close, used when the day has no close of its own
 */
public record ExchangeRate(String baseCurrency, String quoteCurrency, LocalDate rateDate, BigDecimal close,
		BigDecimal previousClose) {

	public ExchangeRate {
		if (close == null && previousClose == null) {
			throw new IllegalArgumentException("An exchange rate without a close or a previous close is useless");
		}
	}

	/**
	 * The spec requires the daily close, falling back to the last close on weekends
	 * and holidays, which is exactly what the provider exposes as
	 * {@code previous_close}.
	 */
	public BigDecimal effectiveRate() {
		return (this.close != null) ? this.close : this.previousClose;
	}

	/**
	 * A stored row whose both columns are null would silently produce a zero rate,
	 * so it is treated as no rate at all.
	 */
	public boolean effectiveRateIsPositive() {
		BigDecimal rate = effectiveRate();
		return rate != null && rate.signum() > 0;
	}

	/**
	 * Reads a stored row.
	 *
	 * <p>
	 * The quote side comes from the caller because {@code currency_rate} has no
	 * column for it: storing both orientations of a pair would give two rows that
	 * can drift apart, so only the base side is persisted and the quote is implied
	 * by the ledger.
	 */
	public static ExchangeRate from(CurrencyRate rate, String ledgerCurrencyCode) {
		return new ExchangeRate(rate.getBaseCurrency().getCode(), ledgerCurrencyCode, rate.getRateDate(),
				rate.getClose(), rate.getPreviousClose());
	}

	/**
	 * The rate of a transaction already expressed in the ledger currency: one, with
	 * nothing to fetch.
	 */
	public static ExchangeRate identity(String ledgerCurrencyCode, LocalDate date) {
		return new ExchangeRate(ledgerCurrencyCode, ledgerCurrencyCode, date, BigDecimal.ONE, null);
	}

	public String pairKey() {
		return this.baseCurrency + "/" + this.quoteCurrency;
	}

}
