package com.idfinance.financial_transactions.dto;

import java.time.Instant;

/**
 * A tracked currency: whether the daily sweep is paying for its rate, and since
 * when.
 *
 * <p>
 * {@code active} is the whole answer to "what does this cost me", which is why
 * it is the field a caller comes here for. {@code createdAt} is the row's own
 * timestamp, not a setting: it records when the currency started being tracked,
 * and it does not move when the currency is deactivated and reactivated.
 *
 * @param code
 *            ISO 4217 code of the tracked currency
 * @param active
 *            whether the daily sweep fetches its rate
 * @param createdAt
 *            when the currency was added to the tracked set
 */
public record CurrencyPairResponse(String code, boolean active, Instant createdAt) {
}
