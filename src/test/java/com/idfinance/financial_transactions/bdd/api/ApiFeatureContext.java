package com.idfinance.financial_transactions.bdd.api;

import com.idfinance.financial_transactions.TestcontainersConfiguration;
import com.idfinance.financial_transactions.bdd.support.BddInfrastructureConfiguration;
import io.cucumber.spring.CucumberContextConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The Spring context for the features that call the API over HTTP.
 *
 * <p>
 * {@code RANDOM_PORT} is the difference from the service suite, and it is what
 * requirement 6 asks for: a real HTTP port rather than a mocked web layer.
 */

@CucumberContextConfiguration
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(value = {TestcontainersConfiguration.class, BddInfrastructureConfiguration.class})
public class ApiFeatureContext {
}
