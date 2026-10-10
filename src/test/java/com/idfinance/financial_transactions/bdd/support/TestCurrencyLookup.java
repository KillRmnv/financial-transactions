package com.idfinance.financial_transactions.bdd.support;

import com.idfinance.financial_transactions.repository.CurrencyRepository;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The currencies the scenarios ask for, put in the lookup when the context
 * starts.
 *
 * <p>
 * The migration seeds the ledger currency and the two the specification names,
 * and the rest is what {@code CurrencyImporter} brings in from the provider.
 * That is right for a deployment and useless for a suite: the stub provider
 * publishes two codes, so every scenario working in anything else would be
 * refused with 404 for a reason that has nothing to do with what it is testing.
 *
 * <p>
 * <strong>Seeded here rather than in the migration, on purpose.</strong> These
 * are test data, and test data belongs in the test sources — which is also what
 * makes this the mirror of the change that moved the ISO 4217 list out of
 * {@code V1}: the codes a suite needs are declared next to the suite.
 *
 * <p>
 * <strong>Synchronous, unlike {@code CurrencyBootstrap}.</strong> That one
 * hands its work to a virtual thread so a slow provider cannot hold the
 * startup, and exactly that property makes it unusable here — a scenario
 * asserting about the lookup would race it, and pass or fail depending on
 * scheduling. This runs while the context is building, so every scenario starts
 * against a lookup in a known state.
 *
 * <p>
 * Idempotent, because the context is shared across every scenario in a suite
 * and may be cached between runs: {@code insertIfAbsent} makes a second call
 * add nothing rather than fail.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestCurrencyLookup {

	/**
	 * What the scenarios need beyond the seed, and what the stub provider does not
	 * publish. A code that must be <em>absent</em> is deliberately not here —
	 * {@code XXX} and {@code ZZZ} stay unknown so the refusal scenarios keep
	 * testing a refusal.
	 */
	private static final List<Map.Entry<String, String>> REQUIRED = List.of(Map.entry("EUR", "Euro"),
			Map.entry("GBP", "Pound Sterling"), Map.entry("CHF", "Swiss Franc"), Map.entry("JPY", "Japanese Yen"));

	/**
	 * Fills the lookup before any scenario runs.
	 */
	@Bean
	public TestCurrencySeeder testCurrencySeeder(CurrencyRepository currencyRepository,
			TransactionTemplate transactionTemplate) {
		return new TestCurrencySeeder(currencyRepository, transactionTemplate);
	}

	/**
	 * The seeding itself, kept out of the configuration class so the
	 * {@code @PostConstruct} proxy is not what a scenario depends on.
	 */
	@Slf4j
	@RequiredArgsConstructor
	public static final class TestCurrencySeeder {

		private final CurrencyRepository currencyRepository;

		private final TransactionTemplate transactionTemplate;

		/**
		 * In a transaction of its own because {@code insertIfAbsent} is a modifying
		 * native query, and Hibernate refuses one outside a transaction with
		 * {@code TransactionRequiredException}. A {@code @PostConstruct} has no ambient
		 * transaction to join: the bean is being created, so no request and no
		 * scheduled method opened one.
		 */
		@PostConstruct
		void seed() {
			// Boxed deliberately: TransactionTemplate answers null when it rolls back
			// rather
			// than throwing, and a rolled-back seed would otherwise look like a zero.
			Integer added = this.transactionTemplate.execute((status) -> {
				int inserted = 0;
				for (Map.Entry<String, String> currency : REQUIRED) {
					inserted += this.currencyRepository.insertIfAbsent(currency.getKey(), currency.getValue());
				}
				return inserted;
			});
			if (added != null && added > 0) {
				log.info("Seeded {} currencies the scenarios need", added);
			}
		}

	}

}
