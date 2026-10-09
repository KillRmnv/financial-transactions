package com.idfinance.financial_transactions.bdd.support;

import com.idfinance.financial_transactions.config.LedgerPolicy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The rates provider for the suites that stub it in-process.
 *
 * <p>
 * Separate from {@link BddInfrastructureConfiguration} so a suite chooses which
 * kind of stub it gets. An in-process bean replaces the whole client, which
 * makes those scenarios fast and precise about the service's own behaviour; it
 * also means the adapter's request — the path, the query parameters, the
 * response it parses — is never exercised. The API suite uses
 * {@link WireMockRatesConfiguration} instead, for exactly that reason.
 */
@TestConfiguration(proxyBeanMethods = false)
public class StubRatesConfiguration {

	@Bean
	@Primary
	public StubRatesProvider stubRatesProvider(LedgerPolicy ledgerPolicy) {
		return new StubRatesProvider(ledgerPolicy);
	}

}
