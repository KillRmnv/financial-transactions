package com.idfinance.financial_transactions.domain;

import com.idfinance.financial_transactions.entity.CurrencyRate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * A daily exchange rate for one pair, together with the rule that picks between
 * {@code close} and {@code previous_close}.
 *
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
 * @param source
 *            the provider that supplied this rate, carried so a stored row can
 *            be traced to the one that answered. It travels with the rate
 *            rather than being asked of the provider at write time, because
 *            with a fallback in play the provider that answered is not
 *            necessarily the provider that was asked first — and a rate
 *            recorded under the wrong name is worse than one recorded under
 *            none
 */
public record ExchangeRate(String baseCurrency, String quoteCurrency, LocalDate rateDate, BigDecimal close,
		BigDecimal previousClose, String source) {

	/**
	 * The source of a rate that came from nowhere because it needed no provider:
	 * one unit of the ledger currency is one unit of itself.
	 */
	public static final String IDENTITY_SOURCE = "identity";

	/**
	 * Digits kept when a rate is inverted. Wider than the eight the column stores,
	 * so the division rounds once at the end rather than inheriting the scale of a
	 * rate it has not seen yet, and narrower than {@code BigDecimal} allows itself
	 * to carry, which would make a comparison between two rates of different
	 * provenance a question of scale rather than of value.
	 */
	private static final int INVERSE_SCALE = 12;

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
				rate.getClose(), rate.getPreviousClose(), rate.getSource());
	}

	/**
	 * The rate of a transaction already expressed in the ledger currency: one, with
	 * nothing to fetch.
	 */
	public static ExchangeRate identity(String ledgerCurrencyCode, LocalDate date) {
		return new ExchangeRate(ledgerCurrencyCode, ledgerCurrencyCode, date, BigDecimal.ONE, null, IDENTITY_SOURCE);
	}

	/**
	 * The same rate read the other way round.
	 *
	 * <p>
	 * Both sides invert, and {@code previous_close} with them. The fallback is a
	 * rate of the same pair on another day, so leaving it upright would mix an
	 * upright close with an inverted fallback and produce a number belonging to
	 * neither orientation.
	 *
	 * @return the rate with the sides swapped; a null side stays null, so an absent
	 *         close does not become a zero and then trip the check that rejects a
	 *         rate with nothing usable in it
	 */
	public ExchangeRate inverted() {
		return new ExchangeRate(this.quoteCurrency, this.baseCurrency, this.rateDate, invert(this.close),
				invert(this.previousClose), this.source);
	}

	private static BigDecimal invert(BigDecimal rate) {
		return (rate == null || rate.signum() == 0)
				? null
				: BigDecimal.ONE.divide(rate, INVERSE_SCALE, RoundingMode.HALF_UP);
	}

	public String pairKey() {
		return this.baseCurrency + "/" + this.quoteCurrency;
	}

}
