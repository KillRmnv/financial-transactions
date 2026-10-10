package com.idfinance.financial_transactions;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * The weekly currency sweep with its own switch turned off.
 *
 * <p>
 * {@code app.currencies.weekly-import.enabled=false} is what a deployment on a
 * free plan sets: the daily rate sweep is what the specification asks for, and
 * the currency listing costs a request a week to learn nothing most weeks.
 *
 * <p>
 * The point of this class is that the setting has to be a supported
 * configuration and not merely a line in a property file.
 * {@code app.scheduling.enabled} is left at its default here, so the bean would
 * exist and register its cron if the new flag were ignored — the test is asking
 * about the new flag alone.
 *
 * <p>
 * A separate context from the BDD suites because the property is read at
 * startup and those share one context across all of their scenarios.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "app.currencies.weekly-import.enabled=false")
class WeeklyCurrencyImportDisabledTests {

	@Autowired
	private ApplicationContext applicationContext;

	@Autowired
	private ObjectProvider<com.idfinance.financial_transactions.client.WeeklyCurrencyImport> weeklyCurrencyImport;

	@Test
	void theSweepIsNotRegisteredAtAll() {
		// Absent rather than present-and-idle: a bean that exists with a no-op method
		// still costs a scheduled registration and still tempts someone into calling it
		// directly, and neither is wanted when the answer is "off".
		Assertions.assertThat(this.weeklyCurrencyImport.getIfAvailable())
				.as("the weekly currency sweep with app.currencies.weekly-import.enabled=false").isNull();

		Assertions
				.assertThat(this.applicationContext
						.getBeanNamesForType(com.idfinance.financial_transactions.client.WeeklyCurrencyImport.class))
				.as("bean names of any kind, including a lazy or skipped registration").isEmpty();
	}

	@Test
	void theDailyRateSweepIsUnaffected() {
		// The two flags answer different questions, and turning one off must not turn
		// off the other: the specification requires the daily sweep of KZT/USD and
		// RUB/USD.
		Assertions
				.assertThat(this.applicationContext
						.getBeanNamesForType(com.idfinance.financial_transactions.service.ScheduledRatesFetcher.class))
				.as("the daily rate sweep must survive the weekly one being switched off").isNotEmpty();
	}

}
