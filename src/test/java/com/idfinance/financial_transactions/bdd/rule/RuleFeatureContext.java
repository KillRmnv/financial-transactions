package com.idfinance.financial_transactions.bdd.rule;

import io.cucumber.spring.CucumberContextConfiguration;
import org.springframework.test.context.ContextConfiguration;

/**
 * The context for the pure-decision features: {@code @ContextConfiguration}
 * with no Boot and no database, which is what keeps the flag rule and the
 * ledger policy testable in milliseconds.
 */

@CucumberContextConfiguration
@ContextConfiguration
public class RuleFeatureContext {
}
