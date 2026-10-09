package com.idfinance.financial_transactions.bdd.support;

import com.idfinance.financial_transactions.SettableClock;
import com.idfinance.financial_transactions.config.LedgerPolicy;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;

/**
 * The infrastructure every BDD suite needs: a clock the scenarios can move, and
 * a rates provider that answers without a network.
 *
 * <p>
 * Boot 4 contributes no {@code RestClient.Builder} bean of its own, so the HTTP
 * suite gets one here rather than assuming it exists.
 *
 * <p>
 * The provider is a stub in every sense: it holds a
 * {@link StubRatesProvider.Mode} a scenario sets, and only the ledger currency
 * needs no rate at all. A transaction in any other currency falls through to
 * the real lookup order, which is what the currency and conversion scenarios
 * assert on.
 */

@TestConfiguration(proxyBeanMethods = false)
public class BddInfrastructureConfiguration {
	@Bean
	@Primary
	public SettableClock settableClock() {
		return new SettableClock(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);
	}

	@Bean
	@Primary
	public StubRatesProvider stubRatesProvider(LedgerPolicy ledgerPolicy) {
		return new StubRatesProvider(ledgerPolicy);
	}

	@Bean
	@Primary
	public RestClient.Builder restClientBuilder() {
		return RestClient.builder();
	}
}
