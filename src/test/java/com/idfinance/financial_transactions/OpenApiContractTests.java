package com.idfinance.financial_transactions;

import com.idfinance.financial_transactions.bdd.support.BddInfrastructureConfiguration;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The API contract is a build artefact and the specification asks for one.
 *
 * <p>
 * It is produced in {@code quality.yml} by a container started in the
 * {@code dev} profile, because the deployment runs {@code prod} where the
 * endpoint is off on purpose — {@code /v3/api-docs} describes the whole
 * internal surface. That is the right trade, and it has a cost: the only thing
 * that checks the document is a workflow that runs nightly, on {@code master},
 * and by hand. Everything {@code ci.yml} runs passes whether the contract is
 * generated at all.
 *
 * <p>
 * So a dependency bump can pass this whole suite and leave the document empty.
 * That is not hypothetical: {@code springdoc} is the one version in
 * {@code pom.xml} pinned outside the Boot parent precisely because it tracks
 * the Spring version, so it is the dependency most likely to change what it
 * emits while changing nothing the compiler or the tests can see.
 *
 * <p>
 * <strong>What this deliberately does not assert:</strong> that the component
 * schemas carry the wire names. They do not — {@code TransactionResponse} is
 * described with {@code limitSum}, {@code sumInLedger} and
 * {@code ledgerCurrency}, while the body it describes is {@code limit_sum},
 * {@code sum_in_ledger} and {@code ledger_currency}. The examples and the query
 * parameters are right, because those are literal values rather than generated
 * property names. springdoc 3.1.0 does ship a
 * {@code PropertyNamingStrategyConverter}, but it reads the naming strategy of
 * the {@code ObjectMapper} globally, and {@code @JsonNaming} sets one per type
 * — so it has nothing to read. Moving it to
 * {@code spring.jackson.property-naming-strategy} would give the contract the
 * right names and would also rename what the Redis rate cache writes, which is
 * a wider change than it looks. See {@code docs/known-gaps.md}.
 *
 * <p>
 * A separate context from the BDD suites because it needs the running web
 * application, and its subject is the contract rather than the specification's
 * behaviour — putting it in a feature file would make it look like a claim
 * about the API rather than about the machinery that describes it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(value = {TestcontainersConfiguration.class, BddInfrastructureConfiguration.class})
@TestPropertySource(properties = "app.scheduling.enabled=false")
class OpenApiContractTests {

	private final RestClient restClient;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Autowired
	public OpenApiContractTests(RestClient.Builder builder, Environment environment) {
		this.restClient = builder.baseUrl("http://localhost:" + environment.getProperty("local.server.port")).build();
	}

	@Test
	void theDocumentDescribesBothApiGroups() {
		String document = this.restClient.get().uri("/v3/api-docs").retrieve().body(String.class);
		JsonNode paths = this.objectMapper.readTree(document).path("paths");

		SoftAssertions.assertSoftly((assertions) -> {
			assertions.assertThat(paths.size()).as("paths described by the contract").isGreaterThan(0);
			assertions.assertThat(paths.has("/api/v1/transactions")).as("the ingestion path").isTrue();
			assertions.assertThat(paths.has("/api/v1/limits")).as("the client-facing path").isTrue();
		});
	}
}
