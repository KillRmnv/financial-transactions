package com.idfinance.financial_transactions.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * An ISO 4217 currency.
 *
 * <p>
 * The table exists so that the currency code is a <em>verified</em> reference
 * rather than a string anyone may spell: the schema refuses a lowercase code
 * outright, so {@code kzt} can never sit next to {@code KZT} as two different
 * things.
 *
 * <p>
 * The seed in {@code V1__init.sql} covers the active codes, and
 * {@code WeeklyCurrencyImport} adds codes that appear later. New currencies are
 * rare, which is why the sweep is weekly rather than daily.
 */
@Entity
@Table(name = "currency")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class Currency {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** Always uppercase, enforced by {@code ck_currency_code_uppercase}. */
	@Column(name = "code", nullable = false, length = 3)
	private String code;

	@Column(name = "display_name", nullable = false, length = 64)
	private String displayName;

	@Column(name = "created_at", nullable = false, insertable = false, updatable = false)
	private Instant createdAt;

	public Currency(String code, String displayName) {
		this.code = code;
		this.displayName = displayName;
	}

	@Override
	public String toString() {
		return this.code;
	}

}
