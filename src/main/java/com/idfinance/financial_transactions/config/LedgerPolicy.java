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
 * {@link RoundingMode#HALF_UP}: the usual commercial choice, and the one that
 * matches what the specification's tables assume.
 *
 * <p>
 * The currency code is validated on binding. A typo in a property would
 * otherwise surface much later as an empty lookup, and a ledger silently
 * denominated in nothing is exactly the failure this class exists to prevent.
 */
@ConfigurationProperties(prefix = "app.ledger")
public record LedgerPolicy(String currencyCode, int amountScale) {

	/**
	 * @param currencyCode
	 *            ISO 4217 code of the ledger currency, uppercase
	 * @param amountScale
	 *            number of decimal places an amount is stored and reported with
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
	 * Rounds an amount the way every amount in this application is rounded.
	 *
	 * <p>
	 * Called once, on the result of a conversion, never on a rate: rounding the
	 * rate before multiplying would throw away the only precision the conversion
	 * has.
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
