package com.idfinance.financial_transactions.config;

import java.math.BigDecimal;
import java.time.Clock;
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

}
