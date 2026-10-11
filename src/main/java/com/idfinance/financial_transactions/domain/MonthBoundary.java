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
	 * The day {@code occurredAt} falls on, in the configured zone.
	 *
	 * <p>
	 * The rate the specification asks for is the one "on the day of the expense",
	 * and this is the day of the expense for the same reason {@link #monthOf} is
	 * the month of it: the sender's offset is not consulted. A transaction posted
	 * at 2022-01-31T23:00+06:00 is 2022-01-31 in this zone and its rate is the one
	 * for that date, whichever offset it arrived with.
	 *
	 * <p>
	 * This used to be the first of the month. It was a defensible choice — one rate
	 * per month, so a limit cannot be crossed by the currency moving inside it —
	 * and it was not what the document said, which is the distinction worth
	 * keeping: the document says "the exchange rate on the day of the expense, or
	 * the last closing rate".
	 */
	public LocalDate dayOf(Instant occurredAt) {
		return occurredAt.atZone(this.zone).toLocalDate();
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
