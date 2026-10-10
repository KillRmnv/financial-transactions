package com.idfinance.financial_transactions.bdd.service;

import com.idfinance.financial_transactions.TestcontainersConfiguration;
import com.idfinance.financial_transactions.bdd.support.BddInfrastructureConfiguration;
import com.idfinance.financial_transactions.bdd.support.StubRatesConfiguration;
import com.idfinance.financial_transactions.bdd.support.TestCurrencyLookup;
import io.cucumber.spring.CucumberContextConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * The Spring context for the service-level features: a real database, no HTTP.
 */

@CucumberContextConfiguration
@SpringBootTest
// Both provider URLs empty, so the fallback composite is not created and the
// in-process
// stub is the only RatesProvider in the context. Without this the two of them
// are both
// @Primary and the context refuses to start — which is the honest failure,
// since a
// composite over a stub has nothing to fall back to.
@TestPropertySource(properties = {"app.rates.base-url=", "app.rates.frankfurter.base-url="})
@Import(value = {TestcontainersConfiguration.class, BddInfrastructureConfiguration.class, StubRatesConfiguration.class,
		TestCurrencyLookup.class})
public class ServiceFeatureContext {
}
