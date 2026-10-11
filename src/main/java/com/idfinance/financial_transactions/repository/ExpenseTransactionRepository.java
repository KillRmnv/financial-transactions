package com.idfinance.financial_transactions.repository;

import com.idfinance.financial_transactions.entity.ExpenseTransaction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExpenseTransactionRepository extends JpaRepository<ExpenseTransaction, Long> {

	/**
	 * What the month already holds against a limit, before the transaction being
	 * processed.
	 *
	 * <p>
	 * Summed by weight rather than by cost, because that is what a limit is
	 * measured against. Under a rule that weighs everything at one this is the
	 * month's spending; under a weighted one it is what the month's spending is
	 * worth, and the weight of each transaction is read from its own row rather
	 * than from the category's current configuration, so that changing a weight
	 * later cannot change what an already-decided flag meant.
	 *
	 * <p>
	 * A row with no conversion contributes nothing: {@code null} in the
	 * multiplication makes the sum skip it, and {@code coalesce} turns a month with
	 * no such rows into zero rather than into null.
	 *
	 * <p>
	 * A new limit does not reset the month: transactions that predate it still
	 * count, which is what makes the remaining balance 900 rather than 2000 on
	 * 10.01.
	 */
	@Query("""
			select coalesce(sum(t.sumInLedger * t.limitWeight), 0)
			from ExpenseTransaction t
			where t.accountFrom = :accountFrom
			  and t.categoryId = :categoryId
			  and t.occurredMonth = :month
			""")
	BigDecimal spentInLedgerForMonth(@Param("accountFrom") String accountFrom, @Param("categoryId") Long categoryId,
			@Param("month") LocalDate month);

	/**
	 * Transactions that exceeded the limit, each carrying the limit its flag was
	 * computed against.
	 *
	 * <p>
	 * The join happens at the reporting stage against the stored {@code limitId},
	 * not against a date comparison: resolving the limit by date would report the
	 * wrong limit for past transactions as soon as a new one is installed.
	 *
	 * @param categoryId
	 *            narrows the report to one category, or null for every category —
	 *            the same null-means-everything the limit report uses, so a caller
	 *            without a filter and one without a category do the same thing
	 */
	@Query("""
			select t from ExpenseTransaction t
			where t.accountFrom = :accountFrom
			  and t.occurredMonth = :month
			  and t.limitExceeded = true
			  and (:categoryId is null or t.categoryId = :categoryId)
			order by t.occurredAt asc, t.id asc
			""")
	List<ExpenseTransaction> findExceededInMonth(@Param("accountFrom") String accountFrom,
			@Param("month") LocalDate month, @Param("categoryId") Long categoryId);

}
