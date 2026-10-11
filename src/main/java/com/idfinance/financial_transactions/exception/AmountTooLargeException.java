package com.idfinance.financial_transactions.exception;

import java.math.BigDecimal;

/**
 * A sum the API accepts converts to a total the ledger column cannot store.
 *
 * <p>
 * Named after the failure rather than after the arithmetic that produced it,
 * because the arithmetic is not what the caller did wrong and the caller cannot
 * check it in advance: the sum was within the boundary, and the rate that made
 * the product too large came from a provider rather than from the request.
 *
 * <p>
 * This exists because {@code ledger_sum} is {@code sum × close} and
 * {@code close} is untrusted. Only a rate below ten keeps a sum at the accepted
 * ceiling inside {@code numeric(20, 8)}; nothing in the schema states that, and
 * nothing can. Without this exception the row fails on insert and the caller
 * receives a constraint it does not know the name of, attached to a field that
 * passed validation.
 */
public class AmountTooLargeException extends DomainException {

	private final BigDecimal sum;

	private final BigDecimal sumInLedger;

	public AmountTooLargeException(BigDecimal sum, BigDecimal sumInLedger) {
		super("A sum of %s converts to %s, which is more than the ledger can store".formatted(sum, sumInLedger),
				"amount-too-large");
		this.sum = sum;
		this.sumInLedger = sumInLedger;
	}

	public BigDecimal sum() {
		return this.sum;
	}

	public BigDecimal sumInLedger() {
		return this.sumInLedger;
	}

}
