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
import org.assertj.core.api.Assertions;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * The service with the weighted rule in force.
 *
 * <p>
 * {@code app.limits.policy=weighted} is a configuration an operator can choose,
 * so it has to be a working configuration rather than something that merely
 * appears in a property file. Two things about it can only be seen through the
 * whole path, and neither is visible from the rule on its own: the weight has
 * to reach the row, and the month's total has to be summed by it.
 *
 * <p>
 * The second is the one that would pass every unit test and still be wrong. A
 * rule comparing a weighted transaction against a total summed from raw costs
 * would hold the first transaction and release the second — the total says 300
 * spent, the rule has seen 150, and the flag is decided against a number that
 * is not there.
 *
 * <p>
 * A separate context from {@code ServiceFeatureTest} because the rule is chosen
 * at startup and the BDD suite shares one context across all of its scenarios,
 * which are all about the specification's rule.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"app.limits.policy=weighted", "app.limits.weights.service=0.5",
		"app.scheduling.enabled=false"})
class WeightedLimitPolicyTests {

	private static final String ACCOUNT = "0000000998";

	private static final String COUNTERPART = "9999999999";

	private final TransactionService transactionService;

	private final ExpenseTransactionRepository expenseTransactionRepository;

	@BeforeEach
	void forgetWhatEarlierTestsLeft() {
		this.expenseTransactionRepository.deleteAll();
	}

	@Test
	void storesTheWeightTheRuleGaveIt() {
		this.transactionService.accept(usdOf("100", "service", "2022-01-05"));
		this.transactionService.accept(usdOf("100", "product", "2022-01-06"));

		List<ExpenseTransaction> stored = this.expenseTransactionRepository.findAll();

		// The weights, not the flags. A row that stored one for both would still pass
		// every flag
		// assertion below, and the monthly total would then be the same number the
		// rule would have computed without configuration.
		SoftAssertions softly = new SoftAssertions();
		softly.assertThat(weightOf(stored, "2022-01-05")).as("a weighted category").isEqualByComparingTo("0.5");
		softly.assertThat(weightOf(stored, "2022-01-06")).as("a category the weights leave out")
				.isEqualByComparingTo("1");
		softly.assertAll();
	}

	@Test
	void weighsTheMonthsTotalAndNotJustTheTransactionInHand() {
		this.transactionService.accept(usdOf("800", "service", "2022-01-05"));

		// 800 counted as 400 against a 1000 default. The first transaction cannot tell
		// the two
		// sums apart, because there is nothing before it.
		Assertions.assertThat(flagOn("2022-01-05")).as("800 counted as 400 is inside the limit").isFalse();

		// 600 more, counted as 300: 700 in all against 1000. A total summed from raw
		// costs
		// would
		// read 1400 and pass this one, so this is the row that says the month's total
		// is
		// weighted
		// and not merely the transaction in hand.
		this.transactionService.accept(usdOf("600", "service", "2022-01-06"));

		Assertions.assertThat(flagOn("2022-01-06")).as("700 counted out of a 1000 limit").isFalse();

		// One more: 301 counted as 150.5, so 850.5 in all, still inside.
		this.transactionService.accept(usdOf("301", "service", "2022-01-07"));

		Assertions.assertThat(flagOn("2022-01-07")).as("850.5 counted out of a 1000 limit").isFalse();

		// And one that crosses it: 400 counts as 200, making 1050.5.
		this.transactionService.accept(usdOf("400", "service", "2022-01-08"));

		Assertions.assertThat(flagOn("2022-01-08")).as("1050.5 counted out of a 1000 limit").isTrue();
	}

	/**
	 * Each category is measured against its own limit, so a weighted one and an
	 * unweighted one never meet.
	 *
	 * <p>
	 * Stated rather than assumed, because it is the one thing about this
	 * configuration that is easy to believe wrongly: 400 weighted and 400
	 * unweighted in the same month are 400 against one allowance and 400 against
	 * another, not 800 against a shared one. A single shared allowance would read
	 * 1100 here and flag the second.
	 */
	@Test
	void eachCategoryIsMeasuredAgainstItsOwnLimit() {
		this.transactionService.accept(usdOf("800", "service", "2022-01-05"));
		this.transactionService.accept(usdOf("700", "product", "2022-01-06"));

		SoftAssertions softly = new SoftAssertions();
		softly.assertThat(flagOn("2022-01-05")).as("800 counted as 400 against the service limit").isFalse();
		softly.assertThat(flagOn("2022-01-06")).as("700 against the product limit").isFalse();
		softly.assertAll();
	}

	private boolean flagOn(String isoDate) {
		return this.expenseTransactionRepository.findAll().stream().filter((transaction) -> onDay(transaction, isoDate))
				.map(ExpenseTransaction::getLimitExceeded).findFirst()
				.orElseThrow(() -> new AssertionError("no transaction stored on " + isoDate));
	}

	private BigDecimal weightOf(List<ExpenseTransaction> stored, String isoDate) {
		return stored.stream().filter((transaction) -> onDay(transaction, isoDate))
				.map(ExpenseTransaction::getLimitWeight).findFirst()
				.orElseThrow(() -> new AssertionError("no transaction stored on " + isoDate));
	}

	private boolean onDay(ExpenseTransaction transaction, String isoDate) {
		return transaction.getOccurredAt().atZone(ZoneOffset.UTC).toLocalDate().toString().equals(isoDate);
	}

	private TransactionRequest usdOf(String sum, String category, String isoDate) {
		OffsetDateTime moment = LocalDate.parse(isoDate).atStartOfDay().atOffset(ZoneOffset.UTC);
		return new TransactionRequest(ACCOUNT, COUNTERPART, "USD", new BigDecimal(sum), category, moment);
	}

	@Autowired
	public WeightedLimitPolicyTests(TransactionService transactionService,
			ExpenseTransactionRepository expenseTransactionRepository) {
		this.transactionService = transactionService;
		this.expenseTransactionRepository = expenseTransactionRepository;
	}

}
