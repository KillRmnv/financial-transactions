package com.idfinance.financial_transactions.bdd.quota;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.ConfigurationParameters;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

/**
 * Runs the quota features against a context whose ingestion quota allows three
 * requests per hour.
 *
 * <p>
 * Separate from the API suite because the limiters live for the lifetime of the
 * context: sharing one would let the API scenarios spend the quota these
 * scenarios are trying to exhaust.
 */

@Suite
@IncludeEngines(value = {"cucumber"})
@SelectClasspathResource(value = "features/quota")
@ConfigurationParameters(value = {
		@ConfigurationParameter(key = "cucumber.glue", value = "com.idfinance.financial_transactions.bdd.quota"),
		@ConfigurationParameter(key = "cucumber.plugin", value = "summary")})
class QuotaFeatureTest {
	QuotaFeatureTest() {
	}
}
