package com.idfinance.financial_transactions.config;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Makes the daily sweep run on one instance rather than on every instance.
 *
 * <p>
 * A {@code @Scheduled} method fires wherever it is registered, and a
 * horizontally scaled deployment registers it once per replica. Two replicas
 * mean two sweeps of the same currency set within the same second, and the
 * specification is explicit that every external request costs money — so the
 * second one is money spent learning what the first already paid for.
 *
 * <p>
 * ShedLock's {@code @SchedulerLock} is an aspect, which is the usual objection
 * in this codebase (see the rate limiter, which uses resilience4j's core API
 * precisely because an annotation cannot key by anything but the method). That
 * reasoning does not apply here: a scheduled sweep has exactly one invocation
 * point and needs exactly one lock, which is the shape an aspect handles well.
 * The thing an annotation cannot express — a quota per calling address — is not
 * what is being asked for.
 *
 * <p>
 * The JDBC provider rather than the Redis one, on purpose. Redis is a soft
 * dependency in this service by decision: an unreachable Redis must cost a
 * round trip and nothing else. A lock table is not that; it belongs next to the
 * data it protects, and a deployment with no Redis should still get a single
 * sweep.
 *
 * <p>
 * {@code lockAtLeastFor} is deliberately absent. Without it the first instance
 * to finish releases the lock immediately and a slow replica could run the
 * sweep in the same minute anyway; with it, a sweep that returns in two seconds
 * still holds the lock for a moment, which is the whole point.
 */
@Configuration(proxyBeanMethods = false)
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public final class SchedulerLockConfig {

	/**
	 * The table {@code V2__shedlock.sql} creates. Named here as well because
	 * ShedLock's default is {@code shedlock} and a mismatch is a runtime error at
	 * the first sweep rather than at startup.
	 *
	 * <p>
	 * The column names are deliberately <em>not</em> configured: this provider
	 * takes the table and otherwise ShedLock's own defaults, {@code lock_until},
	 * {@code locked_at}, {@code locked_by}. They have to match the migration
	 * exactly, and the mismatch is the same kind of failure as the table name — a
	 * schema that exists and a job that fails the first time it runs. V2 named the
	 * first one {@code locked_until}, and {@code V6__shedlock_lock_until.sql} is
	 * what corrected it; renaming a column in an applied migration is what Flyway
	 * refuses outright.
	 */
	private static final String LOCK_TABLE = "shedlock";

	/**
	 * Built here rather than contributed by an auto-configuration: ShedLock ships
	 * no {@code AutoConfiguration.imports}, so declaring the provider as a
	 * parameter of another bean asks for something nothing creates. The failure is
	 * {@code NoSuchBeanDefinitionException}, and it appears as a context that will
	 * not start rather than as a missing lock.
	 *
	 * @param dataSource
	 *            the same database the application uses; the lock table sits next
	 *            to the data it protects
	 * @param transactionManager
	 *            so acquiring a lock runs inside a transaction — the lock row is
	 *            written and read as one unit
	 */
	@Bean
	public LockProvider lockProvider(DataSource dataSource, PlatformTransactionManager transactionManager) {
		return new JdbcTemplateLockProvider(new JdbcTemplate(dataSource), transactionManager, LOCK_TABLE);
	}

	/**
	 * The lock names, in one place so a scenario and the annotation cannot disagree
	 * about the string.
	 */
	public static final class Names {

		/**
		 * One lock for the whole daily sweep rather than one per currency: the
		 * currencies are fetched in parallel inside a single invocation, so a lock per
		 * currency would protect nothing this does not already protect.
		 */
		public static final String DAILY_RATES = "daily-rates";

		/**
		 * The weekly currency import takes its own lock, named after the
		 * {@code scheduled_job} row it consults so the two are visibly the same thing.
		 *
		 * <p>
		 * It is a second holder rather than a shared one: the two jobs run at different
		 * hours on different days, and one lock between them would let a slow import
		 * suppress a rate sweep, which is the one that has to happen daily.
		 */
		public static final String WEEKLY_CURRENCY_IMPORT = "weekly-currency-import";

		private Names() {
		}

	}

}
