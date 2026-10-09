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

	public SettableClock(Instant instant, ZoneId zone) {
		this.zone = zone;
		this.instant = instant;
	}

	public void set(Instant newInstant) {
		this.instant = newInstant;
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
