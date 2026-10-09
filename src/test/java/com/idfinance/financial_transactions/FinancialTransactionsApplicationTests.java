package com.idfinance.financial_transactions;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The context loads. Not a claim about behaviour — the behaviour is in the
 * feature suites — but the one check that Flyway applied, the beans wired and
 * the whole application can start.
 */

@Import(value = {TestcontainersConfiguration.class})
@SpringBootTest
class FinancialTransactionsApplicationTests {
	FinancialTransactionsApplicationTests() {
	}

	@Test
	void contextLoads() {
	}
}
