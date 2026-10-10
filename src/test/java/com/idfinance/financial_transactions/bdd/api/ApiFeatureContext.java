package com.idfinance.financial_transactions.bdd.api;

import com.idfinance.financial_transactions.TestcontainersConfiguration;
import com.idfinance.financial_transactions.bdd.support.BddInfrastructureConfiguration;
import com.idfinance.financial_transactions.bdd.support.TestCurrencyLookup;
import com.idfinance.financial_transactions.bdd.support.WireMockPort;
import com.idfinance.financial_transactions.bdd.support.WireMockRatesConfiguration;
import io.cucumber.spring.CucumberContextConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * The Spring context for the features that call the API over HTTP.
 *
 * <p>
 * {@code RANDOM_PORT} is the difference from the service suite, and it is what
 * requirement 6 asks for: a real HTTP port rather than a mocked web layer.
 *
 * <p>
 * The external rates provider is a WireMock server rather than an in-process
 * bean, also because requirement 6 says so, and because it is the only way the
 * adapter's own request is exercised at all: the real
 * {@code TwelveDataRatesProvider} is registered, pointed at the stub through
 * {@code app.rates.providers.twelvedata.base-url}, set below.
 */

@CucumberContextConfiguration
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(value = {TestcontainersConfiguration.class, BddInfrastructureConfiguration.class, TestCurrencyLookup.class,
		WireMockRatesConfiguration.class})

@TestPropertySource(properties = {"app.rate-limits.ingestion.limit-for-period=100000",
		"app.rate-limits.ingestion.limit-refresh-period=1ms", "app.rate-limits.limit-install.limit-for-period=100000",
		"app.rate-limits.limit-install.limit-refresh-period=1ms",
		// Both providers point at the WireMock server. Declared here rather than on
		// WireMockRatesConfiguration because @TestPropertySource on an imported
		// configuration class is not applied — and a provider left with the URL from
		// application.yml registers anyway, so the suite would quietly talk to the
		// real Frankfurter instead of failing.
		"app.rates.providers.twelvedata.base-url=http://localhost:" + WireMockPort.VALUE,
		"app.rates.providers.frankfurter.base-url=http://localhost:" + WireMockPort.VALUE,
		"app.rates.providers.twelvedata.source=wiremock"})
public class ApiFeatureContext {
}
