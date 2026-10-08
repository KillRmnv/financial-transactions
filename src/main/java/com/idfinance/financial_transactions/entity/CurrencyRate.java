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
 * A daily rate for one currency against USD.
 *
 * <p>
 * Only the {@code base/USD} orientation is stored. The inverse is derived by
 * division, and persisting it as well would give two rows that can drift apart.
 *
 * <p>
 * The base currency is a reference to {@link Currency}, not a string: the
 * schema must reject a code that is not in the lookup, and the entity should
 * express the same thing in its types rather than leaving a {@code String} to
 * be checked somewhere else.
 */
@Entity
@Table(name = "currency_rate")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CurrencyRate {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "base_currency", referencedColumnName = "code", nullable = false)
	private Currency baseCurrency;

	@Column(name = "rate_date", nullable = false)
	private LocalDate rateDate;

	@Column(name = "close", precision = 19, scale = 8)
	private BigDecimal close;

	@Column(name = "previous_close", precision = 19, scale = 8)
	private BigDecimal previousClose;

	/** Which provider supplied the value, so history survives a provider change. */
	@Column(name = "source", nullable = false, length = 32)
	private String source;

	@Column(name = "fetched_at", nullable = false, insertable = false, updatable = false)
	private Instant fetchedAt;

	public CurrencyRate(Currency baseCurrency, LocalDate rateDate, BigDecimal close, BigDecimal previousClose,
			String source) {
		this.baseCurrency = baseCurrency;
		this.rateDate = rateDate;
		this.close = close;
		this.previousClose = previousClose;
		this.source = source;
	}

	/**
	 * Called when the provider republishes a day. A rate already present for the
	 * pair and day is refreshed rather than duplicated, which is what the unique
	 * constraint enforces.
	 */
	public void refresh(BigDecimal newClose, BigDecimal newPreviousClose) {
		this.close = newClose;
		this.previousClose = newPreviousClose;
	}

}
