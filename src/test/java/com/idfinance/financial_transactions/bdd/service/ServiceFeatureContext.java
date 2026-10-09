package com.idfinance.financial_transactions.bdd.service;

import com.idfinance.financial_transactions.TestcontainersConfiguration;
import com.idfinance.financial_transactions.bdd.support.BddInfrastructureConfiguration;
import com.idfinance.financial_transactions.bdd.support.StubRatesConfiguration;
import io.cucumber.spring.CucumberContextConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The Spring context for the service-level features: a real database, no HTTP.
 */

@CucumberContextConfiguration
@SpringBootTest
@Import(value = {TestcontainersConfiguration.class, BddInfrastructureConfiguration.class, StubRatesConfiguration.class})
public class ServiceFeatureContext {
}
