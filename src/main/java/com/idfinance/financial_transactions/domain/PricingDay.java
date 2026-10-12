package com.idfinance.financial_transactions.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/**
 * The calendar date that actually prices a given one.
 *
 * <p>
 * Not the same question as {@link MonthBoundary#monthOf}, which answers "when
 * was this" — that is a fact about the transaction and is stored on the row.
 * This one answers "which rate does this day get priced with", and the answer
 * for two days in a week is a third day.
 *
 * <p>
 * A Saturday and a Sunday have no close of their own, so the specification
 * prices them with the last close before: Friday. Deciding that here rather
 * than after the provider fails to answer saves a request per swept currency
 * per weekend day, and — more to the point — it means the date written to
 * {@code currency_rate.rate_date} is the date the row really holds, so a
 * Saturday's transaction and the row that priced it agree.
 *
 * <p>
 * <strong>Holidays are deliberately not in here.</strong> New Year's Day and
 * Constitution Day fall on weekdays and have no close, and nothing outside this
 * service can tell such a day from one the provider simply missed. A calendar
 * of holidays would have to be maintained per market and would be wrong in
 * every year it was not updated, so those days still take the adapter's
 * fallback: the requested day, then a second request over a window.
 *
 * <p>
 * <strong>The rule is about exchange rates, not about every
 * instrument.</strong> Foreign exchange does not trade at the weekend; crypto
 * does, and {@code currency_pair} is a table an operator edits without a
 * release, so a crypto pair added to it is traded seven days a week. This rule
 * is applied on the way to the provider for the tracked currencies, which the
 * specification names as FX pairs. A day-traded instrument needs the plain
 * date, and adding one means giving it a rule of its own rather than widening
 * this one.
 */
public final class PricingDay {

	/**
	 * @param date
	 *            the day a transaction happened, or the day before "now"
	 * @return {@code date} itself, or the Friday before it when it is a Saturday or
	 *         a Sunday
	 */
	public static LocalDate of(LocalDate date) {
		if (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
			return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.FRIDAY));
		}
		return date;
	}

	private PricingDay() {
	}

}
