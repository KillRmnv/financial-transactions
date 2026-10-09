package com.idfinance.financial_transactions.service;

import com.idfinance.financial_transactions.domain.MonthBoundary;
import com.idfinance.financial_transactions.entity.ExpenseCategory;
import com.idfinance.financial_transactions.entity.ExpenseLimit;
import com.idfinance.financial_transactions.entity.ExpenseTransaction;
import com.idfinance.financial_transactions.repository.ExpenseCategoryRepository;
import com.idfinance.financial_transactions.repository.ExpenseLimitRepository;
import com.idfinance.financial_transactions.repository.ExpenseTransactionRepository;
import com.idfinance.financial_transactions.repository.LimitReportRow;
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
		return reportOf(accountFrom);
	}

	/**
	 * One statement for the whole report, so listing a hundred limits is still one
	 * round trip rather than a hundred and one.
	 */
	private List<LimitRow> reportOf(String accountFrom) {
		LocalDate month = currentMonth();
		return this.expenseLimitRepository.reportForAccount(accountFrom, month).stream().map(this::toRow).toList();
	}

	/**
	 * One limit by id, used to answer the install call without a second definition
	 * of the shape.
	 *
	 * <p>
	 * Answered from the same report as the listing rather than from
	 * {@code findById}, so the installed response and the list response cannot
	 * describe the same limit differently.
	 */
	@Transactional(readOnly = true)
	public Optional<LimitRow> limitRowOf(String accountFrom, Long limitId) {
		return reportOf(accountFrom).stream().filter(row -> row.id().equals(limitId)).findFirst();
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

	/**
	 * The report already carries the category and the currency, so the row is
	 * assembled rather than read. The epoch is turned into a null here: a limit
	 * dated 1 January 1970 is what the standing fallback looks like in the
	 * database, and telling a client that would be worse than telling it nothing.
	 */
	private LimitRow toRow(LimitReportRow row) {
		OffsetDateTime installedAt = row.getLimitDatetime().equals(Instant.EPOCH)
				? null
				: row.getLimitDatetime().atZone(this.zone).toOffsetDateTime();
		BigDecimal spent = row.getSpent();
		return new LimitRow(row.getId(), row.getExpenseCategory(), row.getLimitSum(), installedAt, spent,
				row.getLimitSum().subtract(spent), row.getCurrencyShortname());
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

	/**
	 * Still a lookup per row on the exceeded path, where the rows carry only a
	 * category id.
	 *
	 * <p>
	 * It could join instead, and {@code reportForAccount} shows how, but the
	 * exceeded report is read once per client request over a month's rows, not per
	 * limit, so the count does not grow with the account's history the way the
	 * limit report's did.
	 */
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
