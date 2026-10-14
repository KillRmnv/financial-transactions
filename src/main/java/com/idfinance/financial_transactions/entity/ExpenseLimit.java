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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A monthly spend limit. Limits are append-only: the specification forbids
 * updating an existing limit, and the entity exposes no mutator for
 * {@code amount} or {@code createdAt} so that mistake cannot be made by
 * accident.
 *
 * <p>
 * {@code accountFrom} is the client's own account. The specification defines it
 * as a ten digit string and says nothing more about accounts, so there is
 * deliberately no separate account table: normalising it would require facts
 * about accounts that nobody has.
 */
@Entity
@Table(name = "expense_limit")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public final class ExpenseLimit {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "account_from", nullable = false, length = 10)
	private String accountFrom;

	@Column(name = "category_id", nullable = false)
	private Long categoryId;

	/**
	 * Mirrors the column in V4; see the note on the same field in
	 * {@code ExpenseTransaction}, which is where the reason is written out.
	 */
	@Column(name = "amount", nullable = false, precision = 20, scale = 8)
	private BigDecimal amount;

	/** A limit is always denominated in the currency the ledger is kept in. */
	@ManyToOne(fetch = FetchType.EAGER, optional = false)
	@JoinColumn(name = "currency_id", nullable = false)
	private Currency currency;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	/**
	 * Marks the automatically created limit, which the database uses to deduplicate
	 * it.
	 */
	@Column(name = "is_default", nullable = false)
	private boolean defaultLimit;

	public ExpenseLimit(String accountFrom, Long categoryId, Currency currency, BigDecimal amount, Instant createdAt) {
		this.accountFrom = accountFrom;
		this.categoryId = categoryId;
		this.currency = currency;
		this.amount = amount;
		this.createdAt = createdAt;
	}

}
