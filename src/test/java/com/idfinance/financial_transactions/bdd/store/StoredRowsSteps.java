package com.idfinance.financial_transactions.bdd.store;

import com.idfinance.financial_transactions.entity.ExpenseTransaction;
import com.idfinance.financial_transactions.repository.ExpenseTransactionRepository;
import io.cucumber.java.en.Then;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.assertj.core.api.Assertions;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Steps that read what is already stored, shared by the service and HTTP
 * suites.
 *
 * <p>
 * They live here rather than in either suite so that the two do not drift
 * apart: a copy of "the row holds this much" in both would be two assertions to
 * keep in step, and the disagreement would show up as one suite passing and the
 * other not.
 *
 * <p>
 * These steps are about the database, not about the response. A 201 only proves
 * the request was accepted; what was persisted is a separate question, and it
 * is the one that catches a value lost on the way in.
 */
class StoredRowsSteps {

	@Autowired
	private ExpenseTransactionRepository expenseTransactionRepository;

	@Then("{int} transactions are stored")
	void transactionsAreStored(int expected) {
		Assertions.assertThat(this.expenseTransactionRepository.findAll()).as("rows stored").hasSize(expected);
	}

	@Then("the transaction of {string} totals {string} USD")
	void theTransactionTotals(String isoDate, String expected) {
		Assertions.assertThat(transactionOf(isoDate).getSumInLedger()).as("the converted amount stored for %s", isoDate)
				.isEqualByComparingTo(new BigDecimal(expected));
	}

	@Then("the transaction of {string} is not flagged")
	void theTransactionIsNotFlagged(String isoDate) {
		Assertions.assertThat(flagOf(isoDate)).as("the flag of %s", isoDate).isFalse();
	}

	private ExpenseTransaction transactionOf(String isoDate) {
		LocalDate day = LocalDate.parse(isoDate);
		return this.expenseTransactionRepository.findAll().stream()
				.filter((transaction) -> transaction.getOccurredAt().atZone(ZoneOffset.UTC).toLocalDate().equals(day))
				.findFirst().orElseThrow(() -> new AssertionError("No transaction stored on " + day));
	}

	private boolean flagOf(String isoDate) {
		Boolean flag = transactionOf(isoDate).getLimitExceeded();
		Assertions.assertThat(flag).as("the transaction of %s carries a flag at all", isoDate).isNotNull();
		return flag;
	}

}
