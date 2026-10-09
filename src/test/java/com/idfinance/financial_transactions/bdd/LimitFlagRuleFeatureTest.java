package com.idfinance.financial_transactions.bdd;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.ConfigurationParameters;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

/**
 * Runs the flag-rule features: pure functions, no Spring context beyond the
 * empty one Cucumber builds, no database. These are the scenarios that have to
 * stay fast, because they are the ones a change to the rule will break.
 */

@Suite
@IncludeEngines(value = {"cucumber"})
@SelectClasspathResource(value = "features/rule")
@ConfigurationParameters(value = {
		@ConfigurationParameter(key = "cucumber.glue", value = "com.idfinance.financial_transactions.bdd.rule"),
		@ConfigurationParameter(key = "cucumber.plugin", value = "summary")})
class LimitFlagRuleFeatureTest {
	LimitFlagRuleFeatureTest() {
	}
}
