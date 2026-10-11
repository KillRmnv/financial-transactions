package com.idfinance.financial_transactions;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/**
 * A clock the scenarios can move.
 *
 * <p>
 * Every time-dependent decision in the application reads an injected
 * {@link Clock}, which is what makes the specification's truth tables
 * reproducible at all: a scenario places a limit or a transaction on a date by
 * setting this, and nothing reaches for {@code Instant.now()} behind its back.
 *
 * <p>
 * {@code instant} is volatile rather than final, which is the one mutable field
 * in the test sources. It has to be: the field is the whole point of the class,
 * and it is written from the test thread and read from the application threads
 * of a concurrent-posting scenario.
 */
public class SettableClock extends Clock {

	private final ZoneId zone;

	private volatile Instant instant;

	/**
	 * Where {@link #reset()} puts it back to, since the field is mutable on
	 * purpose.
	 */
	private final Instant startingInstant;

	public SettableClock(Instant instant, ZoneId zone) {
		this.zone = zone;
		this.instant = instant;
		this.startingInstant = instant;
	}

	public void set(Instant newInstant) {
		this.instant = newInstant;
	}

	/**
	 * Back to where this clock started.
	 *
	 * <p>
	 * Scenarios share one of these, and installing a limit moves it. Without this a
	 * scenario inherits whatever instant the one before it left behind, so a
	 * question about "the current month" is answered about whichever month ran
	 * last.
	 */
	public void reset() {
		this.instant = this.startingInstant;
	}

	@Override
	public ZoneId getZone() {
		return this.zone;
	}

	@Override
	public Clock withZone(ZoneId newZone) {
		return new SettableClock(this.instant, newZone);
	}

	@Override
	public Instant instant() {
		return this.instant;
	}

}
