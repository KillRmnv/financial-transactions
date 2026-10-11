package com.idfinance.financial_transactions.config;

import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * How long the two rate-resolving phases may take.
 *
 * <p>
 * Both are derived from the provider's own timeouts rather than written as
 * numbers, because they mean the same thing: every attempt may spend a connect
 * timeout and a read timeout, there are {@code app.rates.attempts} of them, and
 * there is a pause between. A rate of retries times a timeout is not a figure
 * anyone should have to recompute when the provider gets slower.
 *
 * <p>
 * A record rather than two {@code Duration} beans on purpose: two beans of the
 * same type make every injection point ambiguous, and Spring answers that with
 * {@code NoUniqueBeanDefinitionException} at startup rather than with anything
 * that names the two candidates. One bean carrying both values cannot be
 * injected the wrong way round.
 *
 */
@Component
public record RateTimeouts(RateTimeouts.Rates rates) {

	/**
	 * Nested rather than injected as a collaborator: the derivation needs the
	 * settings, and a second constructor argument that exists only to be
	 * dereferenced is noise.
	 *
	 */
	public record Rates(Duration connectTimeout, Duration readTimeout, int attempts, Duration retryBackoff,
			int fallbackDays) {
	}

	/**
	 * Generous rather than tight. Both phases run their calls concurrently, so the
	 * wait is for the slowest of them and not for their sum; the margin covers a
	 * burst of calls contending on the same connection pool. The bound exists for a
	 * provider that accepts requests and never answers them, not to shave
	 * milliseconds off a healthy one.
	 *
	 * @return how long a batch of transactions may take to resolve its rates
	 */
	public Duration batch() {
		return oneAttempt().multipliedBy(Math.max(1, this.rates.attempts())).plus(this.rates.retryBackoff())
				.plusSeconds(10);
	}

	/**
	 * @return how long one currency of the daily sweep may take
	 */
	public Duration sweep() {
		return oneAttempt().multipliedBy(Math.max(1, this.rates.attempts())).plusSeconds(5);
	}

	private Duration oneAttempt() {
		return this.rates.connectTimeout().plus(this.rates.readTimeout());
	}

}
