package com.idfinance.financial_transactions.exception;

/**
 * The row this transaction needed was held for longer than the service is
 * willing to wait.
 *
 * <p>
 * Raised when {@code lock_timeout} expires while
 * {@code TransactionService.applyFlag} waits for the limit row's
 * {@code PESSIMISTIC_WRITE} lock. PostgreSQL's default of {@code 0} means
 * waiting forever, and forever is the wrong answer for a synchronous API: the
 * request holds a Tomcat thread, a pooled connection and a client socket for as
 * long as it waits, so one contended row turns into every other request for the
 * same client waiting behind it.
 *
 * <p>
 * Named after the failure rather than after the lock or the database, so the
 * next resource with its own wait — a bounded queue, a lease — reuses it. A
 * lock that timed out is the same event as a quota that ran out: the caller
 * should come back.
 *
 * <p>
 * It is retryable and the client can tell that from the status: 503 with a type
 * ending in {@code lock-unavailable}, never a 500. A 500 says the service is
 * broken, and a service that is merely busy should not say so.
 */
public final class LockUnavailableException extends DomainException {

	public LockUnavailableException(String accountFrom, String categoryCode) {
		super("The limit for account %s and category %s is busy; the request can be retried".formatted(accountFrom,
				categoryCode), "lock-unavailable");
	}

}
