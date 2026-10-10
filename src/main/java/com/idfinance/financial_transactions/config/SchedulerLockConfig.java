package com.idfinance.financial_transactions.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Makes the daily sweep run on one instance rather than on every instance.
 *
 * <p>
 * A {@code @Scheduled} method fires wherever it is registered, and a horizontally scaled deployment
 * registers it once per replica. Two replicas mean two sweeps of the same currency set within the same
 * second, and the specification is explicit that every external request costs money — so the second one
 * is money spent learning what the first already paid for.
 *
 * <p>
 * ShedLock's {@code @SchedulerLock} is an aspect, which is the usual objection in this codebase (see the
 * rate limiter, which uses resilience4j's core API precisely because an annotation cannot key by
 * anything but the method). That reasoning does not apply here: a scheduled sweep has exactly one
 * invocation point and needs exactly one lock, which is the shape an aspect handles well. The thing an
 * annotation cannot express — a quota per calling address — is not what is being asked for.
 *
 * <p>
 * The JDBC provider rather than the Redis one, on purpose. Redis is a soft dependency in this service by
 * decision: an unreachable Redis must cost a round trip and nothing else. A lock table is not that; it
 * belongs next to the data it protects, and a deployment with no Redis should still get a single sweep.
 *
 * <p>
 * {@code lockAtLeastFor} is deliberately absent. Without it the first instance to finish releases the
 * lock immediately and a slow replica could run the sweep in the same minute anyway; with it, a sweep
 * that returns in two seconds still holds the lock for a moment, which is the whole point.
 */
@Configuration(proxyBeanMethods = false)
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public final class SchedulerLockConfig {

	/**
	 * @param provider
	 *            over the table {@code V2__shedlock.sql} creates; ShedLock owns its rows and its SQL
	 */
	@Bean
	public LockProvider lockProvider(JdbcTemplateLockProvider provider) {
		return provider;
	}

	/**
	 * The lock names, in one place so a scenario and the annotation cannot disagree about the string.
	 */
	public static final class Names {

		/**
		 * One lock for the whole daily sweep rather than one per currency: the currencies are fetched in
		 * parallel inside a single invocation, so a lock per currency would protect nothing this does not
		 * already protect.
		 */
		public static final String DAILY_RATES = "daily-rates";

		private Names() {
		}

	}

}