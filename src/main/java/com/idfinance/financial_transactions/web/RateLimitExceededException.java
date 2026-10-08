package com.idfinance.financial_transactions.web;

import com.idfinance.financial_transactions.exception.DomainException;

/**
 * The caller's quota for this endpoint is spent.
 *
 * <p>
 * Raised by the rate limit interceptor and turned into a 429 by the single
 * exception handler, rather than written straight to the response: the
 * interceptor has no message converters, and building the body there would mean
 * a second, divergent copy of the {@code ProblemDetail} shape.
 *
 * <p>
 * It lives in the {@code exception} hierarchy, which is why the handler's
 * status mapping covers it without a special case for the source package.
 */
public final class RateLimitExceededException extends DomainException {

	private final long retryAfterSeconds;

	public RateLimitExceededException(String key, long retryAfterSeconds) {
		super("The request quota for %s is exhausted".formatted(key), "rate-limited");
		this.retryAfterSeconds = retryAfterSeconds;
	}

	public long retryAfterSeconds() {
		return this.retryAfterSeconds;
	}

}
