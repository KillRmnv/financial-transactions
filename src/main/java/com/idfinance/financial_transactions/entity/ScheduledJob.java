package com.idfinance.financial_transactions.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Whether a scheduled job runs.
 *
 * <p>
 * A row rather than a property, because {@code @ConditionalOnProperty} is
 * decided once when the context registers bean definitions and cannot be
 * changed afterwards. Switching a paid job off is an {@code UPDATE}, not a
 * restart.
 *
 * <p>
 * Keyed by the name the code already knows: nothing references this table, and
 * a surrogate id would be a column nobody reads.
 */
@Entity
@Table(name = "scheduled_job")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public final class ScheduledJob {

	@Id
	@Column(name = "name", nullable = false, length = 64)
	private String name;

	@Column(name = "enabled", nullable = false)
	private boolean enabled;

	@Column(name = "description", nullable = false, length = 255)
	private String description;

	public ScheduledJob(String name, boolean enabled, String description) {
		this.name = name;
		this.enabled = enabled;
		this.description = description;
	}

	/**
	 * Two methods rather than a setter, for the reason {@code currency_pair} has
	 * them: whether a paid job runs is a decision, and naming the two directions
	 * keeps it from being flipped by accident.
	 */
	public void enable() {
		this.enabled = true;
	}

	public void disable() {
		this.enabled = false;
	}

	/**
	 * Names, so that the string a row is found by and the string the job looks
	 * itself up by cannot drift apart as two literals.
	 */
	public static final class Names {

		/** The daily sweep of the rates for every active currency pair. */
		public static final String DAILY_RATES = "daily-rates";

		/**
		 * The weekly re-read of the provider's currency list. Costs credits, changes
		 * about once a year, and is the one worth switching off on a free plan.
		 */
		public static final String WEEKLY_CURRENCY_IMPORT = "weekly-currency-import";

		private Names() {
		}

	}

}
