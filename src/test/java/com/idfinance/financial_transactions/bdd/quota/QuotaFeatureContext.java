package com.idfinance.financial_transactions.bdd.quota;

import com.idfinance.financial_transactions.TestcontainersConfiguration;
import com.idfinance.financial_transactions.bdd.support.BddInfrastructureConfiguration;
import com.idfinance.financial_transactions.bdd.support.StubRatesConfiguration;
import io.cucumber.spring.CucumberContextConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * The Spring context for the quota features.
 *
 * <p>
 * The quota is overridden here rather than in {@code application.yml}:
 * production allows 60 requests a second, so a test cannot exhaust that without
 * either waiting or becoming flaky. Three requests per hour put the boundary
 * somewhere deterministic, and the code under test is identical either way.
 *
 * <p>
 * This is a separate context from the API suite on purpose. The limiters live
 * in a map inside the interceptor for the lifetime of the context, so sharing
 * one context would let the API scenarios spend the quota the quota scenarios
 * are trying to exhaust.
 */

@CucumberContextConfiguration
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(value = {TestcontainersConfiguration.class, BddInfrastructureConfiguration.class, StubRatesConfiguration.class})
@TestPropertySource(properties = {"app.rate-limits.ingestion.limit-for-period=3",
		"app.rate-limits.ingestion.limit-refresh-period=1h", "app.rate-limits.ingestion.retry-after-seconds=1"})
public class QuotaFeatureContext {
}
