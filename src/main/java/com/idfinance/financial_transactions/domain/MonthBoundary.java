package com.idfinance.financial_transactions.domain;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;

import org.springframework.stereotype.Component;

/**
 * The single place where a transaction timestamp is turned into calendar dates.
 *
 * <p>
 * Transactions arrive with assorted UTC offsets, but month boundaries must be
 * computed in one explicitly chosen timezone, otherwise "the first transaction
 * of March" would depend on which offset the sender happened to use.
 */
@Component
public final class MonthBoundary {

	private final Clock clock;

	private final ZoneId zone;

	public MonthBoundary(Clock clock, ZoneId monthTimezone) {
		this.clock = clock;
		this.zone = monthTimezone;
	}

	/**
	 * @return first day of the month {@code occurredAt} belongs to, in the
	 *         configured zone
	 */
	public LocalDate monthOf(Instant occurredAt) {
		return monthOf(occurredAt, this.zone);
	}

	public static LocalDate monthOf(Instant occurredAt, ZoneId zone) {
		return YearMonth.from(occurredAt.atZone(zone)).atDay(1);
	}

	/**
	 * @return the current date in the configured zone, used to pick the rate of the
	 *         day
	 */
	public LocalDate today() {
		return LocalDate.now(this.clock);
	}

	public Clock clock() {
		return this.clock;
	}

	public ZoneId zone() {
		return this.zone;
	}

}
