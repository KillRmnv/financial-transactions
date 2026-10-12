package com.idfinance.financial_transactions.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The document header served at {@code /v3/api-docs} and rendered at
 * {@code /swagger-ui.html}.
 *
 * <p>
 * A {@link OpenAPI} bean, not {@code springdoc.api-docs.info.*} in YAML:
 * springdoc 3 reads the title and version from this bean and silently falls
 * back to its own defaults otherwise. The YAML block looked correct and was
 * ignored, which is the worst way for a setting to fail.
 *
 * <p>
 * The description carries the two things a client cannot infer from the field
 * names — that {@code sum} arrives in the account's own currency while
 * {@code sumInLedger} is the converted amount, and that a null flag means "no
 * rate was available" rather than {@code false}.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

	private static final String DESCRIPTION = """
			Accepts expense operations in real time, converts them into the ledger currency \
			(`app.ledger.currency-code`, USD by default) and flags the ones whose monthly running total \
			goes over the limit in effect on the transaction date.

			Two things a reader cannot infer from the field names:

			* `sum` arrives in the transaction's own currency; `sum_in_ledger` is that amount converted into \
			the ledger currency. Both it and `limit_exceeded` are null when no exchange rate was available — \
			a missing answer, not `false`.
			* the flag follows a strict comparison, so a remaining balance of exactly zero is **not** \
			exceeded.

			A transaction in a currency outside the lookup is refused with 404; the accepted codes are \
			published at `GET /api/v1/currencies`.
			""";

	@Bean
	OpenAPI financialTransactionsApi() {
		return new OpenAPI().info(new Info().title("Financial transactions API").version("v1").description(DESCRIPTION)
				.license(new License().name("Proprietary")));
	}

}
