package com.idfinance.financial_transactions.service;

import com.idfinance.financial_transactions.config.LedgerPolicy;
import com.idfinance.financial_transactions.domain.MonthBoundary;
import com.idfinance.financial_transactions.entity.ExpenseCategory;
import com.idfinance.financial_transactions.entity.ExpenseLimit;
import com.idfinance.financial_transactions.entity.ExpenseTransaction;
import com.idfinance.financial_transactions.exception.NotFoundException;
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
 *
 * <p>
 * Both reports take an optional category. It is checked here rather than left
 * to the query to answer nothing: a client asking about a category that does
 * not exist has made a mistake worth naming, and an empty list would be
 * indistinguishable from "no limit applies there", which is the one answer the
 * specification forbids.
 */
@Service
@RequiredArgsConstructor
public class ReportingService {

	private final ExpenseLimitRepository expenseLimitRepository;

	private final ExpenseTransactionRepository expenseTransactionRepository;

	private final ExpenseCategoryRepository expenseCategoryRepository;

	private final ZoneId zone;

	private final Clock clock;

	/**
	 * The standing limit for a category nothing was installed for, and the amount
	 * the ingestion path writes when it materialises one.
	 */
	private final BigDecimal defaultLimitSum;

	private final LedgerPolicy ledgerPolicy;

	/**
	 * @param categoryCode
	 *            narrows the report to one category, or null for every category
	 * @throws NotFoundException
	 *             if the category is not one the lookup holds
	 */
	@Transactional(readOnly = true)
	public List<LimitRow> limitsOf(String accountFrom, String categoryCode) {
		checkCategory(categoryCode);
		return reportOf(accountFrom, categoryCode);
	}

	/**
	 * One statement for the whole report, so listing a hundred limits is still one
	 * round trip rather than a hundred and one.
	 *
	 * <p>
	 * The category goes into the query rather than being filtered over the result:
	 * the report is ordered by installation date, so narrowing in memory would keep
	 * the ordering of the whole set only to throw most of it away.
	 */
	private List<LimitRow> reportOf(String accountFrom, String categoryCode) {
		LocalDate month = currentMonth();
		return this.expenseLimitRepository.reportForAccount(accountFrom, month, categoryCode, this.defaultLimitSum,
				this.ledgerPolicy.currencyCode()).stream().map(this::toRow).toList();
	}

	/**
	 * One limit by id, used to answer the install call without a second definition
	 * of the shape.
	 *
	 * <p>
	 * Answered from the same report as the listing rather than from
	 * {@code findById}, so the installed response and the list response cannot
	 * describe the same limit differently.
	 *
	 * <p>
	 * Compared with {@link Objects#equals} because the report also carries
	 * categories with no limit row, and those have no id:
	 * {@code row.id().equals(limitId)} is a null dereference on the first of them,
	 * which would turn an ordinary listing into a 500.
	 */
	@Transactional(readOnly = true)
	public Optional<LimitRow> limitRowOf(String accountFrom, Long limitId) {
		return reportOf(accountFrom, null).stream().filter(row -> Objects.equals(row.id(), limitId)).findFirst();
	}

	/**
	 * Rejects a category the lookup does not hold.
	 *
	 * <p>
	 * Null passes — no filter is not an unknown category. Anything else is resolved
	 * through the same repository the ingestion path uses, so the two cannot
	 * disagree about what a category is, and the refusal is the same
	 * {@link NotFoundException} {@code LimitService} raises when a limit is
	 * installed for one.
	 */
	private void checkCategory(String categoryCode) {
		if (categoryCode != null && this.expenseCategoryRepository.findByCode(categoryCode).isEmpty()) {
			throw NotFoundException.category(categoryCode);
		}
	}

	/**
	 * The id the transaction query filters on, resolved once rather than per row.
	 *
	 * <p>
	 * Null means no filter, which the query reads as "every category" — the same
	 * meaning it has everywhere else here. The category has already been checked,
	 * so the lookup cannot come back empty.
	 */
	private Long categoryIdOf(String categoryCode) {
		return this.expenseCategoryRepository.findByCode(categoryCode).map(ExpenseCategory::getId).orElse(null);
	}

	/**
	 * Transactions that exceeded the limit, each carrying the limit its flag was
	 * computed against.
	 *
	 * <p>
	 * The limits are fetched in one query and matched in memory: resolving them one
	 * at a time would make this an N+1 for a month with many exceeded transactions.
	 *
	 * @param categoryCode
	 *            narrows the report to one category, or null for every category
	 * @throws NotFoundException
	 *             if the category is not one the lookup holds
	 */
	@Transactional(readOnly = true)
	public List<ExceededRow> exceededInMonth(String accountFrom, String categoryCode, LocalDate month) {
		checkCategory(categoryCode);
		LocalDate effectiveMonth = (month != null) ? month.withDayOfMonth(1) : currentMonth();
		List<ExpenseTransaction> exceeded = this.expenseTransactionRepository.findExceededInMonth(accountFrom,
				effectiveMonth, categoryIdOf(categoryCode));

		Map<Long, ExpenseLimit> limitsById = exceeded.stream().map(ExpenseTransaction::getLimitId)
				.filter(Objects::nonNull).distinct().map(this.expenseLimitRepository::findById)
				.flatMap(Optional::stream).collect(Collectors.toMap(ExpenseLimit::getId, Function.identity()));

		return exceeded.stream().map(transaction -> toRow(transaction, categoryCode(transaction.getCategoryId()),
				limitsById.get(transaction.getLimitId()))).toList();
	}

	/**
	 * The report already carries the category and the currency, so the row is
	 * assembled rather than read.
	 *
	 * <p>
	 * Two different things arrive as a missing installation date, and both are the
	 * standing default: the epoch a stored fallback is dated at, and the null of a
	 * category that has no limit row at all. Telling a client that a 1000 USD limit
	 * was installed on 1 January 1970 would be worse than telling it nothing, and a
	 * category nobody has spent on never had an installation either.
	 */
	private LimitRow toRow(LimitReportRow row) {
		Instant installed = row.getLimitDatetime();
		boolean isFallback = (installed == null) || installed.equals(Instant.EPOCH);
		OffsetDateTime installedAt = isFallback ? null : installed.atZone(this.zone).toOffsetDateTime();
		BigDecimal spent = row.getSpent();
		return new LimitRow(row.getId(), row.getExpenseCategory(), row.getLimitSum(), installedAt, spent,
				row.getLimitSum().subtract(spent), row.getCurrencyShortname(), isFallback);
	}

	private ExceededRow toRow(ExpenseTransaction transaction, String expenseCategory, ExpenseLimit limit) {
		return new ExceededRow(transaction, accountFromOf(transaction), expenseCategory, zone, installedAt(limit),
				(limit != null) ? limit.getAmount() : null, (limit != null) ? limit.getCurrency().getCode() : null,
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
	 * @param limitDefault
	 *            true for that fallback, so a client can tell it apart from a limit
	 *            whose installation date is merely unknown
	 */
	public record LimitRow(Long id, String expenseCategory, BigDecimal limitSum, OffsetDateTime limitDatetime,
			BigDecimal spentLedger, BigDecimal remaining, String limitCurrencyShortname, boolean limitDefault) {
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
