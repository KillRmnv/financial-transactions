package com.idfinance.financial_transactions.bdd.support;

import com.idfinance.financial_transactions.SettableClock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;

/**
 * The infrastructure every BDD suite needs: a clock the scenarios can move, and
 * a builder for the HTTP client the scenarios drive the API with.
 *
 * <p>
 * Boot 4 contributes no {@code RestClient.Builder} bean of its own, so the HTTP
 * suites get one here rather than assuming it exists.
 *
 * <p>
 * The rates provider is deliberately not here. A suite either stubs it
 * in-process or points the real client at a WireMock server, and which one it
 * is decides what the suite actually exercises — see {@link StubRatesProvider}
 * and {@link WireMockRatesConfiguration}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class BddInfrastructureConfiguration {

	/**
	 * Starts in 2026, which is after every transaction date the features use, so a
	 * scenario that forgets to move it fails rather than passing by accident.
	 */
	@Bean
	@Primary
	public SettableClock settableClock() {
		return new SettableClock(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);
	}

	@Bean
	@Primary
	public RestClient.Builder restClientBuilder() {
		return RestClient.builder();
	}

}
