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

	@Column(name = "sum", nullable = false, precision = 14, scale = 2)
	private BigDecimal sum;

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

	@Column(name = "limit_exceeded")
	private Boolean limitExceeded;

	/**
	 * Separates "computed and false" from "not computed, no rate was available".
	 */
	@Column(name = "flag_calculated", nullable = false)
	private boolean flagCalculated;

	public ExpenseTransaction(String accountFrom, String accountTo, Currency currency, BigDecimal sum, Long categoryId,
			Instant occurredAt, LocalDate occurredMonth) {
		this.accountFrom = accountFrom;
		this.accountTo = accountTo;
		this.currency = currency;
		this.sum = sum;
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

	public void recordConversion(BigDecimal newSumInLedger, BigDecimal rate, LocalDate date) {
		this.sumInLedger = newSumInLedger;
		this.appliedRate = rate;
		this.rateDate = date;
	}

	/**
	 * Records the limit the flag was computed against.
	 *
	 * <p>
	 * Neither argument may be null here: {@code flagCalculated} would claim the
	 * answer is known while either half of it is missing, which is exactly the
	 * state the schema forbids.
	 *
	 * @throws IllegalArgumentException
	 *             if the limit or the flag is null
	 */
	public void assignFlag(Long newLimitId, Boolean newLimitExceeded) {
		if (newLimitId == null || newLimitExceeded == null) {
			throw new IllegalArgumentException("A computed flag needs both the limit it was compared with and the "
					+ "outcome; use markFlagUncalculated() when no rate was available");
		}
		this.limitId = newLimitId;
		this.limitExceeded = newLimitExceeded;
		this.flagCalculated = true;
	}

	/**
	 * A transaction accepted without a usable rate keeps its data but stays
	 * unflagged, so the reporting endpoints never present an invented {@code false}
	 * as a computed answer.
	 */
	public void markFlagUncalculated() {
		this.limitId = null;
		this.limitExceeded = null;
		this.flagCalculated = false;
	}

}
