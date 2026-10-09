package com.idfinance.financial_transactions.bdd.service;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.ConfigurationParameters;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

/**
 * Runs the service-level features: a real database, no HTTP.
 */

@Suite
@IncludeEngines(value = {"cucumber"})
@SelectClasspathResource(value = "features/service")
@ConfigurationParameters(value = {
		@ConfigurationParameter(key = "cucumber.glue", value = "com.idfinance.financial_transactions.bdd.service,com.idfinance.financial_transactions.bdd.store"),
		@ConfigurationParameter(key = "cucumber.plugin", value = "summary")})
class ServiceFeatureTest {
	ServiceFeatureTest() {
	}
}
