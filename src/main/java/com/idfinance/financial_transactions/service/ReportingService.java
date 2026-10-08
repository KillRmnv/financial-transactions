package com.idfinance.financial_transactions.service;

import com.idfinance.financial_transactions.domain.MonthBoundary;
import com.idfinance.financial_transactions.entity.ExpenseCategory;
import com.idfinance.financial_transactions.entity.ExpenseLimit;
import com.idfinance.financial_transactions.entity.ExpenseTransaction;
import com.idfinance.financial_transactions.repository.ExpenseCategoryRepository;
import com.idfinance.financial_transactions.repository.ExpenseLimitRepository;
import com.idfinance.financial_transactions.repository.ExpenseTransactionRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only queries behind the client-facing API: the limits of an account, and
 * the transactions that exceeded one.
 *
 * <p>
 * Reporting lives in its own service because it needs the same tables as the
 * ingestion path for entirely different reasons, and mixing the two hides the
 * flag logic.
 */
@Service
@RequiredArgsConstructor
public class ReportingService {

	private final ExpenseLimitRepository expenseLimitRepository;

	private final ExpenseTransactionRepository expenseTransactionRepository;

	private final ExpenseCategoryRepository expenseCategoryRepository;

	private final ZoneId zone;

	private final Clock clock;

	@Transactional(readOnly = true)
	public List<LimitRow> limitsOf(String accountFrom) {
		LocalDate month = currentMonth();
		return this.expenseLimitRepository.findAllForAccount(accountFrom).stream().map(limit -> toRow(limit, month))
				.toList();
	}

	/**
	 * One limit by id, used to answer the install call without a second definition
	 * of the shape.
	 */
	@Transactional(readOnly = true)
	public Optional<LimitRow> limitRowOf(String accountFrom, Long limitId) {
		return this.expenseLimitRepository.findById(limitId).filter(limit -> limit.getAccountFrom().equals(accountFrom))
				.map(limit -> toRow(limit, currentMonth()));
	}

	/**
	 * Transactions that exceeded the limit, each carrying the limit its flag was
	 * computed against.
	 *
	 * <p>
	 * The limits are fetched in one query and matched in memory: resolving them one
	 * at a time would make this an N+1 for a month with many exceeded transactions.
	 */
	@Transactional(readOnly = true)
	public List<ExceededRow> exceededInMonth(String accountFrom, LocalDate month) {
		LocalDate effectiveMonth = (month != null) ? month.withDayOfMonth(1) : currentMonth();
		List<ExpenseTransaction> exceeded = this.expenseTransactionRepository.findExceededInMonth(accountFrom,
				effectiveMonth);

		Map<Long, ExpenseLimit> limitsById = exceeded.stream().map(ExpenseTransaction::getLimitId)
				.filter(Objects::nonNull).distinct().map(this.expenseLimitRepository::findById)
				.flatMap(Optional::stream).collect(Collectors.toMap(ExpenseLimit::getId, Function.identity()));

		return exceeded.stream().map(transaction -> toRow(transaction, categoryCode(transaction.getCategoryId()),
				limitsById.get(transaction.getLimitId()))).toList();
	}

	private LimitRow toRow(ExpenseLimit limit, LocalDate month) {
		BigDecimal spent = this.expenseTransactionRepository.sumInLedgerForMonth(limit.getAccountFrom(),
				limit.getCategoryId(), month);
		return new LimitRow(limit.getId(), categoryCode(limit.getCategoryId()), limit.getSum(), installedAt(limit),
				spent, limit.getSum().subtract(spent), limit.getCurrency().getCode());
	}

	private ExceededRow toRow(ExpenseTransaction transaction, String expenseCategory, ExpenseLimit limit) {
		return new ExceededRow(transaction, accountFromOf(transaction), expenseCategory, zone, installedAt(limit),
				(limit != null) ? limit.getSum() : null, (limit != null) ? limit.getCurrency().getCode() : null,
				transaction.getCurrency().getCode());
	}

	/**
	 * @return when the limit was installed, or null for the standing 1000 USD
	 *         fallback. That one is dated at the epoch so it is in effect for every
	 *         transaction; reporting the year 1970 to a client would be worse than
	 *         reporting nothing.
	 */
	private OffsetDateTime installedAt(ExpenseLimit limit) {
		if (limit == null || limit.isDefaultLimit()) {
			return null;
		}
		return limit.getCreatedAt().atZone(this.zone).toOffsetDateTime();
	}

	private String accountFromOf(ExpenseTransaction transaction) {
		return transaction.getAccountFrom();
	}

	private String categoryCode(Long categoryId) {
		return this.expenseCategoryRepository.findById(categoryId).map(ExpenseCategory::getCode).orElse(null);
	}

	private LocalDate currentMonth() {
		return MonthBoundary.monthOf(Instant.now(this.clock), this.zone);
	}

	/**
	 * @param remaining
	 *            negative once the month goes over the limit, which is legitimate
	 * @param limitDatetime
	 *            null when the limit is the automatic fallback
	 */
	public record LimitRow(Long id, String expenseCategory, BigDecimal limitSum, OffsetDateTime limitDatetime,
			BigDecimal spentUsd, BigDecimal remainingUsd, String limitCurrencyShortname) {
	}

	/**
	 * @param limitSum
	 *            the limit the flag was computed against; null when the transaction
	 *            was accepted without one
	 * @param limitDatetime
	 *            null for the automatic fallback
	 */
	public record ExceededRow(ExpenseTransaction transaction, String accountFrom, String expenseCategory, ZoneId zone,
			OffsetDateTime limitDatetime, BigDecimal limitSum, String limitCurrencyShortname, String currencyCode) {

		public OffsetDateTime occurredAt() {
			return this.transaction.getOccurredAt().atZone(this.zone).toOffsetDateTime();
		}

	}

}
