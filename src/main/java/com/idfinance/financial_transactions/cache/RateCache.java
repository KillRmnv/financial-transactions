package com.idfinance.financial_transactions.cache;

import com.idfinance.financial_transactions.config.RateCachePolicy;
import com.idfinance.financial_transactions.domain.ExchangeRate;
import com.idfinance.financial_transactions.logging.SafeLog;
import java.time.LocalDate;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Rates in Redis, in front of the table that already holds them.
 *
 * <p>
 * A rate is looked up once per transaction, the table answers it with an
 * indexed query, and a cache in front of that saves a round trip. What it must
 * never do is become a second source of truth, so every answer here is treated
 * as a hint — a miss, a decode failure or an unreachable Redis all fall through
 * to the database and the service carries on as if this class were not there.
 *
 * <h2>Why nothing here throws</h2>
 *
 * <p>
 * Ingestion is the product. A transaction must be stored whether or not the
 * cache answered, so every Redis failure is caught, counted and logged.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public final class RateCache {

	private final StringRedisTemplate redisTemplate;

	private final RateCachePolicy policy;

	private final ObjectMapper objectMapper;

	/**
	 * The rate the chain resolved for a day, or empty.
	 *
	 * <p>
	 * Called with the day that was asked for, which is not necessarily the day the
	 * rate describes — see the class comment.
	 */
	public Optional<ExchangeRate> get(String baseCurrency, String quoteCurrency, LocalDate rateDate) {
		if (!this.policy.enabled()) {
			return Optional.empty();
		}
		String pair = pair(baseCurrency, quoteCurrency);
		try {
			String cached = this.redisTemplate.opsForValue().get(rateKey(pair, rateDate, generationOf(pair)));
			if (cached == null) {
				return Optional.empty();
			}
			return Optional.of(this.objectMapper.readValue(cached, ExchangeRate.class));
		} catch (RedisConnectionFailureException ex) {
			log.warn("The rate cache is unreachable, falling back to the database: {}", SafeLog.failure(ex));
			return Optional.empty();
		} catch (RuntimeException ex) {
			log.warn("Discarding an unreadable rate cache entry for {} on {}: {}", pair, rateDate, SafeLog.failure(ex));
			evict(pair, rateDate);
			return Optional.empty();
		}
	}

	/**
	 * Remembers what the database answered.
	 *
	 * <p>
	 * Only a resolved rate is cached. A miss is deliberately not: caching "there is
	 * no rate for this day" would keep sending transactions down the external path
	 * after the sweep has published one, and the sweep is exactly what makes rates
	 * appear.
	 */
	public void put(String baseCurrency, String quoteCurrency, LocalDate requestedDate, ExchangeRate rate) {
		if (!this.policy.enabled()) {
			return;
		}
		String pair = pair(baseCurrency, quoteCurrency);
		try {
			this.redisTemplate.opsForValue().set(rateKey(pair, requestedDate, generationOf(pair)),
					this.objectMapper.writeValueAsString(rate), this.policy.ttl());
		} catch (RuntimeException ex) {
			log.debug("Could not cache the {} rate for {} on {}: {}", pair, requestedDate, SafeLog.failure(ex));
		}
	}

	/**
	 * Declares everything cached for a pair stale, in one command.
	 *
	 * <p>
	 * Called whenever a rate row is written, which is what makes a refresh visible
	 * immediately rather than at the end of the TTL. Bumping the generation rather
	 * than deleting keys is explained in the class comment.
	 */
	public void invalidatePair(String baseCurrency, String quoteCurrency) {
		if (!this.policy.enabled()) {
			return;
		}
		try {
			this.redisTemplate.opsForValue().increment(generationKey(pair(baseCurrency, quoteCurrency)));
		} catch (RuntimeException ex) {
			log.debug("Could not invalidate the cached rates for {}: {}", pair(baseCurrency, quoteCurrency),
					SafeLog.failure(ex));
		}
	}

	/**
	 * Drops one entry, used when what came back could not be read.
	 */
	private void evict(String pair, LocalDate rateDate) {
		try {
			this.redisTemplate.delete(rateKey(pair, rateDate, generationOf(pair)));
		} catch (RuntimeException ex) {
			log.debug("Could not drop the unreadable rate cache entry for {} on {}: {}", pair, rateDate,
					SafeLog.failure(ex));
		}
	}

	/**
	 * The pair's current generation, read on every lookup.
	 *
	 * <p>
	 * A missing counter is generation zero, which is also the value a pair that was
	 * never cached has, so the common case costs one {@code GET} that returns nil.
	 */
	private long generationOf(String pair) {
		String value = this.redisTemplate.opsForValue().get(generationKey(pair));
		if (value == null) {
			return 0L;
		}
		try {
			return Long.parseLong(value);
		} catch (NumberFormatException ex) {
			log.warn("The rate cache generation for {} is not a number; starting a new one", pair);
			return this.redisTemplate.opsForValue().increment(generationKey(pair));
		}
	}

	private String pair(String baseCurrency, String quoteCurrency) {
		return baseCurrency + "/" + quoteCurrency;
	}

	private String generationKey(String pair) {
		return this.policy.keyPrefix() + pair + ":gen";
	}

	private String rateKey(String pair, LocalDate rateDate, long generation) {
		return this.policy.keyPrefix() + pair + ":" + generation + ":" + rateDate;
	}

}
