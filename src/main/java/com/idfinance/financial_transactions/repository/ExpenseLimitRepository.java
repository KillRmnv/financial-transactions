package com.idfinance.financial_transactions.repository;

import com.idfinance.financial_transactions.entity.ExpenseLimit;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Instant;
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

	@Query("""
			select l from ExpenseLimit l
			where l.accountFrom = :accountFrom
			order by l.createdAt desc, l.id desc
			""")
	List<ExpenseLimit> findAllForAccount(@Param("accountFrom") String accountFrom);

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
			on conflict do nothing
			""", nativeQuery = true)
	int createDefaultIfAbsent(@Param("accountFrom") String accountFrom, @Param("categoryId") Long categoryId,
			@Param("sum") BigDecimal sum, @Param("currencyId") Long currencyId);

}
