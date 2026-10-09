package com.idfinance.financial_transactions.bdd.api;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.ConfigurationParameters;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

/**
 * Runs the HTTP features over a real port, which is requirement 6's end-to-end
 * test.
 *
 * <p>
 * Named {@code *FeatureTest} rather than {@code *IT} because only Surefire is
 * configured, and a class named {@code *IT} would never run under
 * {@code ./mvnw verify}.
 */

@Suite
@IncludeEngines(value = {"cucumber"})
@SelectClasspathResource(value = "features/api")
@ConfigurationParameters(value = {
		@ConfigurationParameter(key = "cucumber.glue", value = "com.idfinance.financial_transactions.bdd.api,com.idfinance.financial_transactions.bdd.store"),
		@ConfigurationParameter(key = "cucumber.plugin", value = "summary")})
class ApiFeatureTest {
	ApiFeatureTest() {
	}
}
