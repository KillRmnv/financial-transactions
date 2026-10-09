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
 * Limits are keyed by the client's own account, which the specification defines
 * as a ten digit string.
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
	 * The month's total is a grouped subquery rather than a second round trip per
	 * row: asking for it once per limit is an N+1 that grows with the number of
	 * limits, and the number of limits grows every time a client raises one.
	 * Aggregating once is also the shape the specification asks for, and it is the
	 * only one that stays a single statement as an account's history grows.
	 *
	 * <p>
	 * {@code left join} rather than {@code inner}: an account with a limit and no
	 * transactions yet is a normal state, and an inner join would hide its limits
	 * exactly when the client is asking what they are.
	 *
	 * <p>
	 * The category and the currency are joined for their codes rather than looked
	 * up per row, for the same reason. A projection has no session to resolve a
	 * {@code ManyToOne} lazily, so an entity-returning query here would be the same
	 * N+1 in a different hat.
	 */
	@Query(value = """
			select l.id                    as id,
			       cat.code                 as expenseCategory,
			       l.sum                    as limitSum,
			       l.created_at             as limitDatetime,
			       cur.code                 as currencyShortname,
			       coalesce(spent.total, 0) as spent
			from expense_limit l
			join expense_category cat on cat.id = l.category_id
			join currency cur on cur.id = l.currency_id
			left join (
			    select t.account_from, t.category_id, sum(t.ledger_sum) as total
			    from expense_transaction t
			    where t.occurred_month = :month
			    group by t.account_from, t.category_id
			) spent on spent.account_from = l.account_from and spent.category_id = l.category_id
			where l.account_from = :accountFrom
			order by l.created_at desc, l.id desc
			""", nativeQuery = true)
	List<LimitReportRow> reportForAccount(@Param("accountFrom") String accountFrom, @Param("month") LocalDate month);

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
			insert into expense_limit (account_from, category_id, sum, currency_id, created_at, is_default)
			select :accountFrom, :categoryId, :sum, :currencyId, to_timestamp(0), true
			where not exists (
			    select 1 from expense_limit l
			    where l.account_from = :accountFrom and l.category_id = :categoryId
			)
			on conflict do nothing
			""", nativeQuery = true)
	int createDefaultIfAbsent(@Param("accountFrom") String accountFrom, @Param("categoryId") Long categoryId,
			@Param("sum") BigDecimal sum, @Param("currencyId") Long currencyId);

}
