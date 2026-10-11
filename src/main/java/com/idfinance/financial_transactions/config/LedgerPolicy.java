package com.idfinance.financial_transactions.config;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The currency the ledger is kept in, and how its amounts are rounded.
 *
 * <p>
 * Limits are denominated in this currency, every transaction is converted into
 * it before the monthly total is compared, and every stored rate is quoted
 * against it. The specification fixes it to USD today, but nothing in the code
 * assumes that: it is a property, so moving the ledger to EUR or CNY is a
 * configuration change plus a data migration, not a rewrite.
 *
 * <p>
 * The rounding mode lives here rather than at the call sites because "how is an
 * amount of money rounded" is one decision, not one per conversion. It is
 * {@link RoundingMode#HALF_UP}.
 *
 * <p>
 * The currency code is validated on binding. A typo in a property would
 * otherwise surface much later as an empty lookup, and a ledger silently
 * denominated in nothing is exactly the failure this class exists to prevent.
 */
@ConfigurationProperties(prefix = "app.ledger")
public record LedgerPolicy(String currencyCode, int amountScale) {

	/**
	 * Integer digits an amount may carry, which is what {@code @Digits} refuses.
	 *
	 * <p>
	 * The columns are {@code numeric(20, 8)} — twelve integer digits — so this is
	 * deliberately one fewer than the column allows. The reason is the conversion
	 * rather than the column: {@code ledger_sum} is {@code sum × close}, and the
	 * rate is not always below one. GBP and EUR are quoted at more than a dollar,
	 * so a sum at the column's own ceiling converts to a product past it and the
	 * row is refused on write. Bounding the input below the column leaves room for
	 * that product, and rejects 10^11 KZT — about 230 million dollars — only.
	 *
	 * <p>
	 * The specification sets no limit on a sum. This is ours, and it is a storage
	 * decision rather than a business rule; {@code docs/assumptions.md} records it
	 * as one.
	 */
	public static final int MAX_AMOUNT_INTEGER_DIGITS = 11;

	/**
	 * Fraction digits an amount may carry, the same eight the columns store. This
	 * is not a return of the two-decimal {@code @Digits} removed in
	 * {@code 22db866}: 0.001 and 0.000061 are amounts, and rounding them to 0.00
	 * before the write is what made the month's running total stop moving.
	 */
	public static final int MAX_AMOUNT_FRACTION_DIGITS = 8;

	/**
	 * The largest sum the API accepts: eleven integer digits and eight fraction
	 * ones.
	 */
	public static final BigDecimal MAX_AMOUNT = new BigDecimal("99999999999.99999999");

	/**
	 * The largest converted total the ledger column holds, {@code numeric(20, 8)}:
	 * twelve integer digits and eight fraction ones.
	 */
	private static final BigDecimal STORAGE_CEILING = new BigDecimal("999999999999.99999999");

	/**
	 * Whether an amount is too large to store once converted.
	 *
	 * <p>
	 * The check the column itself cannot make and the schema cannot express: a
	 * product of a legal sum and a rate the provider supplied is not guaranteed to
	 * fit, because {@code close} is untrusted input and only a rate below ten keeps
	 * it inside. Catching it here answers a 400 naming the field instead of letting
	 * the row fail on insert, where the caller reads a constraint it does not know
	 * about and cannot act on.
	 *
	 * @param amount
	 *            the converted total, never null
	 * @return whether its absolute value is past what the column stores
	 */
	public boolean exceedsStorageCeiling(BigDecimal amount) {
		return amount.abs().compareTo(STORAGE_CEILING) > 0;
	}

	/**
	 * @param currencyCode
	 *            ISO 4217 code of the ledger currency, uppercase
	 * @param amountScale
	 *            number of decimal places an amount is reported with, after storage
	 *            has kept it as it was given
	 * @throws IllegalArgumentException
	 *             if the code is not a three letter uppercase code, or the scale is
	 *             negative
	 */
	public LedgerPolicy {
		if (currencyCode == null || !currencyCode.matches("^[A-Z]{3}$")) {
			throw new IllegalArgumentException(
					"app.ledger.currency-code must be a three letter uppercase ISO 4217 code, got: " + currencyCode);
		}
		if (amountScale < 0) {
			throw new IllegalArgumentException("app.ledger.amount-scale cannot be negative, got: " + amountScale);
		}
	}

	/**
	 * Rounds an amount the way every amount is presented to a client.
	 *
	 * <p>
	 * The display scale, not the storage scale. A limit of 0.001 is a limit and a
	 * transaction in a low-rate currency converts to a fraction of a unit; both are
	 * stored exactly as they arrive, and this is called on the way out — in the
	 * mappers — rather than before the row is written. Called on the input it would
	 * decide what a number is allowed to be rather than how it is shown: rounding
	 * 0.001 before storage produced a limit of 0.00 that the positive-amount check
	 * then refused.
	 *
	 * <p>
	 * Never called on a rate: rounding the rate before multiplying would throw away
	 * the only precision the conversion has.
	 */
	public BigDecimal round(BigDecimal amount) {
		return amount.setScale(this.amountScale, RoundingMode.HALF_UP);
	}

	/**
	 * @param code
	 *            an ISO 4217 code, in whatever case the caller had it
	 * @return whether an amount in that currency is already in the ledger currency,
	 *         and therefore needs no rate at all
	 */
	public boolean isLedgerCurrency(String code) {
		return this.currencyCode.equals(code);
	}

}
