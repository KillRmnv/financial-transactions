package com.idfinance.financial_transactions.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;

/**
 * One expense operation, as accepted by the ingestion API.
 *
 * <p>
 * {@code limitId} records the limit that was actually used to compute the flag,
 * rather than letting a later query resolve it by date. Without it, installing
 * a new limit would make every past transaction report the wrong limit amount
 * and installation time.
 */
@Entity
@Table(name = "expense_transaction")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public final class ExpenseTransaction {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "account_from", nullable = false, length = 10)
	private String accountFrom;

	@Column(name = "account_to", nullable = false, length = 10)
	private String accountTo;

	/**
	 * A verified reference to {@link Currency}; the code itself is not stored
	 * twice.
	 */
	@ManyToOne(fetch = FetchType.EAGER, optional = false)
	@JoinColumn(name = "currency_id", nullable = false)
	private Currency currency;

	@Column(name = "amount", nullable = false, precision = 14, scale = 2)
	private BigDecimal amount;

	@Column(name = "category_id", nullable = false)
	private Long categoryId;

	/**
	 * The original offset is preserved: transactions arrive with assorted
	 * timezones.
	 */
	@Column(name = "occurred_at", nullable = false)
	private Instant occurredAt;

	/**
	 * First day of the month in the configured timezone, so boundaries are not
	 * recomputed.
	 */
	@Column(name = "occurred_month", nullable = false)
	private LocalDate occurredMonth;

	/**
	 * The amount converted into the ledger currency at the time of acceptance.
	 *
	 * <p>
	 * Nullable on purpose: a transaction in a currency with no rate anywhere is
	 * still stored, and inventing a converted amount for it would be worse than
	 * admitting there is none.
	 */
	@Column(name = "ledger_sum", precision = 14, scale = 2)
	private BigDecimal sumInLedger;

	/** In ledger units per one unit of the transaction currency. */
	@Column(name = "applied_rate", precision = 19, scale = 8)
	private BigDecimal appliedRate;

	@Column(name = "rate_date")
	private LocalDate rateDate;

	@Column(name = "limit_id")
	private Long limitId;

	/**
	 * What this transaction counts as against its limit, decided by the rule in
	 * force when it was accepted.
	 *
	 * <p>
	 * Stored rather than read from the category's configuration on every sum, for
	 * the reason {@link #limitId} is stored: raising a weight in June must not
	 * change what January's transactions meant, and a flag already decided cannot
	 * be revisited.
	 *
	 * <p>
	 * One by default, which is the whole of the behaviour of a rule that weighs
	 * nothing.
	 */
	@Column(name = "limit_weight", nullable = false)
	private BigDecimal limitWeight = BigDecimal.ONE;

	@Column(name = "limit_exceeded")
	private Boolean limitExceeded;

	/**
	 * Separates "computed and false" from "not computed, no rate was available" —
	 * by being derived rather than declared.
	 *
	 * <p>
	 * Computed by the database from {@code limit_exceeded}, so the two cannot drift
	 * apart. The CHECK that used to stand here was one-directional: it forbade a
	 * computed flag without an outcome, but happily accepted {@code flagCalculated
	 * = false} next to {@code limitExceeded = true}, which is the one combination
	 * that reads as "no verdict" while carrying one. A generated column has no such
	 * gap, and there is nothing left for application code to forget to set.
	 */
	@Column(name = "flag_calculated", insertable = false, updatable = false)
	@Generated
	private boolean flagCalculated;

	public ExpenseTransaction(String accountFrom, String accountTo, Currency currency, BigDecimal amount,
			Long categoryId, Instant occurredAt, LocalDate occurredMonth) {
		this.accountFrom = accountFrom;
		this.accountTo = accountTo;
		this.currency = currency;
		this.amount = amount;
		this.categoryId = categoryId;
		this.occurredAt = occurredAt;
		this.occurredMonth = occurredMonth;
	}

	/**
	 * Fills in the fields that depend on server-side decisions: which category the
	 * request resolved to and which month the timestamp belongs to in the
	 * configured timezone. A client cannot assert either.
	 */
	public void assign(Long newCategoryId, LocalDate newOccurredMonth) {
		this.categoryId = newCategoryId;
		this.occurredMonth = newOccurredMonth;
	}

	/**
	 * Records what this transaction counts as against a limit.
	 *
	 * <p>
	 * Null is read as "for its full value" rather than refused, for the same reason
	 * the column defaults to one: a rule that weighs nothing has no opinion to
	 * offer, and a transaction accepted while one was in force must not be storable
	 * only under it. A zero or negative weight is a different matter and is refused
	 * — see the CHECK.
	 */
	public void assignWeight(BigDecimal newWeight) {
		if (newWeight != null && newWeight.signum() <= 0) {
			throw new IllegalArgumentException("A transaction counts for its full value or less, never more and never "
					+ "nothing; asked for a weight of " + newWeight);
		}
		this.limitWeight = (newWeight == null) ? BigDecimal.ONE : newWeight;
	}

	public void recordConversion(BigDecimal newSumInLedger, BigDecimal rate, LocalDate date) {
		this.sumInLedger = newSumInLedger;
		this.appliedRate = rate;
		this.rateDate = date;
	}

	/**
	 * Records the limit the flag was computed against.
	 *
	 * <p>
	 * Neither argument may be null here: a flag with no limit behind it, or with no
	 * outcome, is half of a decision, and the row would report itself as
	 * uncalculated while carrying the pieces of an answer.
	 *
	 * <p>
	 * There is no counterpart that clears the flag. A transaction accepted without
	 * a usable rate is simply never assigned one, and {@code flag_calculated} comes
	 * out false because {@code limit_exceeded} was left null — which is the state a
	 * fresh entity is already in, so a method that put it back would be writing a
	 * value that was never anything else.
	 *
	 * @throws IllegalArgumentException
	 *             if the limit or the flag is null
	 */
	public void assignFlag(Long newLimitId, Boolean newLimitExceeded) {
		if (newLimitId == null || newLimitExceeded == null) {
			throw new IllegalArgumentException("A computed flag needs both the limit it was compared with and the "
					+ "outcome; a transaction with no rate is left unassigned instead");
		}
		this.limitId = newLimitId;
		this.limitExceeded = newLimitExceeded;
	}

}
