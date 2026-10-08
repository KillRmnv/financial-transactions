package com.idfinance.financial_transactions.exception;

import java.time.LocalDate;

/**
 * The rate for a currency and day could not be obtained, so the transaction
 * cannot be compared with a USD limit.
 *
 * <p>
 * Kept separate from {@link RatesUnavailableException} because the two mean
 * different things: this one says the request cannot be processed, the other
 * says the provider could not be reached.
 */
public class RateNotFoundException extends DomainException {

	private final String currency;

	private final LocalDate date;

	public RateNotFoundException(String currency, LocalDate date) {
		super("No exchange rate is available for %s/USD on %s, so the amount cannot be compared with a USD limit"
				.formatted(currency, date), "rate-not-found");
		this.currency = currency;
		this.date = date;
	}

	public String currency() {
		return this.currency;
	}

	public LocalDate date() {
		return this.date;
	}

}
