package com.idfinance.financial_transactions.web;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Quotas under {@code app.rate-limits}, one block per
 * {@link RateLimited#quota()} name.
 *
 * <p>
 * These live in configuration rather than in the annotation so a limit can be
 * retuned without a rebuild, and so a test can shrink one to something it can
 * actually exhaust.
 *
 * <p>
 * The map is bound to the {@code rate-limits} key of the {@code app} prefix,
 * which is why the property name of the component matters:
 * {@code app.rate-limits.ingestion.limit-for-period} lands in
 * {@code rateLimits["ingestion"].limitForPeriod}.
 */
@ConfigurationProperties(prefix = "app")
public record RateLimitProperties(Map<String, Quota> rateLimits) {

	public RateLimitProperties {
		rateLimits = (rateLimits != null) ? Map.copyOf(rateLimits) : Map.of();
	}

	/**
	 * A quota, in resilience4j's own vocabulary rather than a token bucket's:
	 * permits granted per refresh period. The two are close, but a permit count per
	 * period is what the limiter actually enforces, so naming it that way keeps the
	 * configuration and the code telling the same story.
	 *
	 * @param limitForPeriod
	 *            how many requests are allowed per {@code limitRefreshPeriod}
	 * @param limitRefreshPeriod
	 *            the period the allowance is counted over
	 * @param retryAfterSeconds
	 *            delay advertised to a refused client
	 */
	public record Quota(int limitForPeriod, Duration limitRefreshPeriod, long retryAfterSeconds) {

	}

}
