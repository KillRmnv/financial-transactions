package com.idfinance.financial_transactions;

import org.springframework.boot.SpringApplication;

/**
 * A dev-time entry point that starts the containers and then runs the
 * application, so {@code ./mvnw spring-boot:run} needs no compose running
 * beside it. Not a test.
 */

public class TestFinancialTransactionsApplication {
	public static void main(String[] args) {
		SpringApplication.from(FinancialTransactionsApplication::main).with(TestcontainersConfiguration.class)
				.run(args);
	}
}
