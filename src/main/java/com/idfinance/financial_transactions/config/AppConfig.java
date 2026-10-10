package com.idfinance.financial_transactions.config;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Wires the beans that the rest of the application depends on but never
 * configures itself.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties({AppProperties.class, LedgerPolicy.class, RateCachePolicy.class})
public final class AppConfig {

	/**
	 * Every time-dependent decision reads this clock instead of calling
	 * {@code Instant.now()} directly, so tests can move time to reproduce the truth
	 * tables from the specification.
	 */
	@Bean
	Clock clock(AppProperties properties) {
		return Clock.system(properties.monthTimezone());
	}

	@Bean
	ZoneId monthTimezone(AppProperties properties) {
		return properties.monthTimezone();
	}

	@Bean
	BigDecimal defaultLimitSum(AppProperties properties) {
		return properties.defaultLimitSum();
	}

	/**
	 * How long one currency may take during the daily sweep before the sweep gives up on it.
	 *
	 * <p>
	 * Derived from the provider's own settings rather than guessed at a number: every attempt can spend a
	 * connect timeout and a read timeout, and there are {@code attempts} of them with a pause between.
	 * The margin covers the pause and the round trip to the provider that has not started yet, so the
	 * bound is generous rather than tight — a currency dropped for being slow is recalculated by the
	 * next sweep, while a sweep that never returns holds a scheduler slot and a lock lease for good.
	 */
	@Bean
	Duration rateSweepTimeout(AppProperties properties) {
		AppProperties.Rates rates = properties.rates();
		Duration perAttempt = rates.connectTimeout().plus(rates.readTimeout());
		Duration all = perAttempt.multipliedBy(Math.max(1, rates.attempts())).plus(rates.retryBackoff());
		return all.plusSeconds(5);
	}

}
