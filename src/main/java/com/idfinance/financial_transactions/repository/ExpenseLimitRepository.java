package com.idfinance.financial_transactions.repository;

import com.idfinance.financial_transactions.entity.ExpenseLimit;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Limits are keyed by the client's own account, which is a ten digit string.
 */
public interface ExpenseLimitRepository extends JpaRepository<ExpenseLimit, Long> {

	/**
	 * The limit in effect on a date, locked for update.
	 *
	 * <p>
	 * This is the serialisation point for concurrent transaction posts: the second
	 * request for the same account and category blocks here until the first
	 * commits, so the two cannot read the same month total.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			select l from ExpenseLimit l
			where l.accountFrom = :accountFrom
			  and l.categoryId = :categoryId
			  and l.createdAt <= :at
			order by l.createdAt desc, l.id desc
			""")
	List<ExpenseLimit> lockEffectiveOnCandidates(@Param("accountFrom") String accountFrom,
			@Param("categoryId") Long categoryId, @Param("at") Instant at);

	default Optional<ExpenseLimit> lockEffectiveOn(String accountFrom, Long categoryId, Instant at) {
		return lockEffectiveOnCandidates(accountFrom, categoryId, at).stream().findFirst();
	}

	/**
	 * Every limit of an account with what the month has already spent against it,
	 * in one query.
	 *
	 * <p>
	 * Driven by {@code expense_category} rather than by {@code expense_limit},
	 * which is the whole difference between this report and the one it replaced. It
	 * used to enumerate the rows that existed, so a client who had never spent on
	 * {@code service} was told about one category, and the omission read as "no
	 * limit applies there" — while the first transaction in that category would in
	 * fact be measured against the standing 1000 USD. The specification keeps the
	 * limit per category and takes an unset one as 1000, so the category exists
	 * whether or not a row was ever written for it.
	 *
	 * <p>
	 * A category with no row comes back as the standing default: the amount and the
	 * ledger currency are coalesced in, {@code created_at} and {@code id} stay
	 * null, and the service reads that null as "the automatic fallback" exactly as
	 * it reads the epoch. No row is written to make it true. Both defaults are
	 * parameters because {@code app.default-limit-sum} and
	 * {@code app.ledger.currency-code} are configuration, and a literal here would
	 * outlive them.
	 *
	 * <p>
	 * The month's total is a grouped subquery rather than a second round trip per
	 * row: asking for it once per limit is an N+1 that grows with the number of
	 * limits, and the number of limits grows every time a client raises one.
	 * Aggregating once is also the shape the specification asks for, and it is the
	 * only one that stays a single statement as an account's history grows. It
	 * joins on the account rather than on {@code l.account_from} so that a category
	 * with no limit still reports what was spent against it.
	 *
	 * <p>
	 * {@code categoryCode} narrows the report to one category, or null for all of
	 * them. The comparison is inside the query rather than a filter over the
	 * result: the rows are ordered by installation date, so a category asked for by
	 * name is answered by the same statement and in the same position it would have
	 * had.
	 *
	 * @param categoryCode
	 *            the category to report on, or null for every category
	 */
	@Query(value = """
			select l.id                                 as id,
			       cat.code                              as expenseCategory,
			       coalesce(l.amount, :defaultSum)       as limitSum,
			       l.created_at                          as limitDatetime,
			       coalesce(cur.code, :ledgerCurrency)   as currencyShortname,
			       coalesce(spent.total, 0)              as spent
			from expense_category cat
			left join expense_limit l
			       on l.account_from = :accountFrom and l.category_id = cat.id
			left join currency cur on cur.id = l.currency_id
			left join (
			    select t.account_from, t.category_id, sum(t.ledger_sum) as total
			    from expense_transaction t
			    where t.occurred_month = :month
			    group by t.account_from, t.category_id
			) spent on spent.account_from = :accountFrom and spent.category_id = cat.id
			where (:categoryCode is null or cat.code = :categoryCode)
			order by l.created_at desc nulls last, l.id desc nulls last
			""", nativeQuery = true)
	List<LimitReportRow> reportForAccount(@Param("accountFrom") String accountFrom, @Param("month") LocalDate month,
			@Param("categoryCode") String categoryCode, @Param("defaultSum") BigDecimal defaultSum,
			@Param("ledgerCurrency") String ledgerCurrency);

	/**
	 * Creates the standing default limit, unless one already exists.
	 *
	 * <p>
	 * The idempotency is enforced by the partial unique index
	 * {@code ux_expense_limit_default}, not by a read-then-write in Java: a check
	 * followed by an insert lets two concurrent first transactions both see "no
	 * limit" and both insert. Here the loser of the race simply gets zero rows back
	 * and carries on with the row the winner inserted.
	 *
	 * <p>
	 * {@code created_at} is the epoch, not "now": a transaction can arrive dated
	 * before the service ever heard of the account, and the fallback has to cover
	 * it.
	 *
	 * @return 1 when this call inserted the row, 0 when one was already there
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(value = """
			insert into expense_limit (account_from, category_id, amount, currency_id, created_at, is_default)
			select :accountFrom, :categoryId, :amount, :currencyId, to_timestamp(0), true
			where not exists (
			    select 1 from expense_limit l
			    where l.account_from = :accountFrom and l.category_id = :categoryId
			      and l.created_at <= :at
			)
			on conflict do nothing
			""", nativeQuery = true)
	int createDefaultIfAbsent(@Param("accountFrom") String accountFrom, @Param("categoryId") Long categoryId,
			@Param("amount") BigDecimal amount, @Param("currencyId") Long currencyId, @Param("at") Instant at);

}
