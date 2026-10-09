package com.idfinance.financial_transactions.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How the Redis rate cache behaves.
 *
 * <p>
 * Redis here is a cache and nothing else: the database remains the source of
 * truth for every rate, and a Redis that is down, slow or unreachable must cost
 * a round trip and nothing else. The specification asks for the rates to be
 * cached "over the main database", which is exactly the relationship — the
 * cache sits in front of a table that answers on its own.
 *
 * @param enabled
 *            whether to consult Redis at all; turning it off leaves every code
 *            path working, which is what makes it safe to disable in a
 *            deployment that does not run Redis
 * @param ttl
 *            how long a cached rate is trusted. Rates are published once a day,
 *            so this only has to outlast the interval between the refresh and
 *            the next one; it is a backstop against a lost invalidation rather
 *            than the primary mechanism, which is the generation counter in
 *            {@code RateCache}
 * @param keyPrefix
 *            namespace, so a shared Redis does not collide with anything else
 *            living in it
 */
@ConfigurationProperties(prefix = "app.cache")
public record RateCachePolicy(boolean enabled, Duration ttl, String keyPrefix) {

	public RateCachePolicy {
		if (ttl == null || ttl.isNegative() || ttl.isZero()) {
			throw new IllegalArgumentException(
					"A cache that expires instantly is not a cache; app.cache.ttl was " + ttl);
		}
		if (keyPrefix == null || keyPrefix.isBlank()) {
			throw new IllegalArgumentException("app.cache.key-prefix must not be blank: an empty prefix would put this "
					+ "service's keys at the root of a Redis that may be shared");
		}
	}

}
