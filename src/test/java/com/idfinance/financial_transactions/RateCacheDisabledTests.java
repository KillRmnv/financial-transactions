package com.idfinance.financial_transactions;

import com.idfinance.financial_transactions.dto.TransactionRequest;
import com.idfinance.financial_transactions.entity.ExpenseTransaction;
import com.idfinance.financial_transactions.repository.ExpenseTransactionRepository;
import com.idfinance.financial_transactions.service.TransactionService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * The service with the cache switched off entirely.
 *
 * <p>
 * {@code app.cache.enabled=false} is the setting a deployment without Redis
 * would use, so it has to be a supported configuration rather than something
 * that merely appears in a property file. Everything the cache does is
 * optional, and this is what proves it: a context where the cache never answers
 * has to produce the same converted amounts, out of the database alone.
 *
 * <p>
 * A separate context from {@code ServiceFeatureTest} because the property is
 * read at startup and the BDD suite shares one context across all of its
 * scenarios.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"app.cache.enabled=false", "app.scheduling.enabled=false"})
class RateCacheDisabledTests {

	private static final String ACCOUNT = "0000000999";

	private static final String COUNTERPART = "9999999999";

	@Autowired
	private TransactionService transactionService;

	@Autowired
	private ExpenseTransactionRepository expenseTransactionRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	/**
	 * The context is shared by both tests and the rate table has a unique key on
	 * (base, rate_date), so the row is replaced rather than inserted: a second
	 * insert would fail on the second test for reasons that have nothing to do with
	 * the cache.
	 */
	@BeforeEach
	void seedRate() {
		this.jdbcTemplate.update("delete from expense_transaction");
		this.jdbcTemplate.update("delete from expense_limit");
		this.jdbcTemplate.update("delete from currency_rate");
		this.jdbcTemplate.update(
				"insert into currency_rate (base_currency, rate_date, close, source) values (?, ?, ?, ?)", "KZT",
				java.sql.Date.valueOf(LocalDate.parse("2022-01-01")), new BigDecimal("0.0025"), "seeded by a test");
	}

	@Test
	void convertsFromTheDatabaseAloneWhenTheCacheIsDisabled() {
		this.transactionService.accept(kztOf("400", "2022-01-05"));
		this.transactionService.accept(kztOf("400", "2022-01-06"));

		List<ExpenseTransaction> stored = this.expenseTransactionRepository.findAll();

		// The converted amount is the subject, not the flag: with no cache to answer,
		// both amounts
		// come from the rate the table holds, and have to match what the cached suite
		// produces for
		// the same rate. Two transactions rather than one, because the second is the
		// one that would
		// go looking for a cached answer.
		SoftAssertions softly = new SoftAssertions();
		softly.assertThat(stored).as("transactions stored without a cache").hasSize(2);
		softly.assertThat(ledgerSumOn(stored, "2022-01-05")).as("the first conversion").isEqualByComparingTo("1.00");
		softly.assertThat(ledgerSumOn(stored, "2022-01-06")).as("the second conversion").isEqualByComparingTo("1.00");
		softly.assertAll();
	}

	@Test
	void stillCalculatesTheFlagWithoutTheCache() {
		this.transactionService.accept(kztOf("400", "2022-01-05"));

		ExpenseTransaction stored = this.expenseTransactionRepository.findAll().getFirst();

		// The flag path is independent of the cache, and asserting only the amount
		// would let a
		// service that converted without flagging pass.
		SoftAssertions softly = new SoftAssertions();
		softly.assertThat(stored.isFlagCalculated()).as("the flag was calculated").isTrue();
		softly.assertThat(stored.getLimitExceeded()).as("400 KZT is far inside the 1000 default").isFalse();
		softly.assertAll();
	}

	private BigDecimal ledgerSumOn(List<ExpenseTransaction> stored, String isoDate) {
		return stored.stream().filter((transaction) -> transaction.getOccurredAt().atZone(ZoneOffset.UTC).toLocalDate()
				.toString().equals(isoDate)).map(ExpenseTransaction::getSumInLedger).findFirst().orElseThrow();
	}

	private TransactionRequest kztOf(String sum, String isoDate) {
		OffsetDateTime moment = LocalDate.parse(isoDate).atStartOfDay().atOffset(ZoneOffset.UTC);
		return new TransactionRequest(ACCOUNT, COUNTERPART, "KZT", new BigDecimal(sum), "product", moment);
	}

}
