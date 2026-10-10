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
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A currency whose rate is kept warm by the daily sweep.
 *
 * <p>
 * The set lives in the database rather than in {@code application.yml} so that
 * an operator can add or retire one without a release. Every sweep costs money,
 * so what is kept warm is an operational decision and belongs where it can be
 * changed with permission.
 *
 * <p>
 * There is no quote column, and that is the point. The ledger currency is a
 * single value from {@code app.ledger.currency-code}, and {@code currency_rate}
 * stores rates without a quote because it is implied. A column here would be a
 * second copy of one configuration value: it could disagree, and then the only
 * correct response would be a check somewhere in the code to catch the
 * disagreement. Instead the quote is supplied at request time, which also means
 * moving the ledger to another currency requires no change to this table at
 * all.
 *
 * <p>
 * Being absent from this table is not a rejection. A transaction in an unlisted
 * currency is accepted and its rate is fetched on demand by
 * {@code ExchangeRateService}; the table only says what has already been
 * pre-fetched.
 */
@Entity
@Table(name = "currency_pair")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public final class CurrencyPair {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.EAGER, optional = false)
	@JoinColumn(name = "base_currency", referencedColumnName = "code", nullable = false)
	private Currency baseCurrency;

	/**
	 * Only active currencies are swept; retiring one keeps the stored rates as
	 * history.
	 */
	@Column(name = "active", nullable = false)
	private boolean active;

	public CurrencyPair(Currency baseCurrency, boolean active) {
		this.baseCurrency = baseCurrency;
		this.active = active;
	}

	@Column(name = "created_at", nullable = false, insertable = false, updatable = false)
	private Instant createdAt;

	/**
	 * Starts sweeping this currency again.
	 *
	 * <p>
	 * Methods rather than a setter, for the same reason the rest of this entity has
	 * none: {@code active} is a decision with a cost attached — every sweep
	 * iteration is a paid request — so its two directions are named separately and
	 * neither is reachable by accident.
	 */
	public void activate() {
		this.active = true;
	}

	/**
	 * Retires this currency from the daily sweep.
	 *
	 * <p>
	 * Deactivating is not deleting: the stored rates stay as history, and an
	 * inactive row still records that the currency was tracked. A transaction in a
	 * deactivated currency is not refused — its rate is fetched on demand instead.
	 */
	public void deactivate() {
		this.active = false;
	}

}
