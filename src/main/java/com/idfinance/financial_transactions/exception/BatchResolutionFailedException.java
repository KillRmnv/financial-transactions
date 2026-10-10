package com.idfinance.financial_transactions.exception;

/**
 * A batch of transactions could not have its rates resolved within the time
 * allowed.
 *
 * <p>
 * The only way to reach this is a provider that accepted the calls and did not
 * answer them, or answered too slowly for the whole batch to wait. It is
 * deliberately its own type rather than a message on
 * {@code ExternalServiceUnavailableException}: the caller can retry the batch,
 * and retrying a request whose parts may already have been stored is a
 * different decision from retrying a request that stored nothing.
 *
 * <p>
 * Nothing was written when this is thrown — the rate phase is the first thing a
 * batch does, and the store phase has not started — so the caller may safely
 * send the same batch again. That property is why it is not raised from the
 * store phase.
 */
public final class BatchResolutionFailedException extends DomainException {

	public BatchResolutionFailedException(int transactions, Throwable cause) {
		super("Could not obtain the exchange rates for a batch of %d transactions within the time allowed"
				.formatted(transactions), "batch-resolution-failed", cause);
	}

}
