package com.idfinance.financial_transactions.bdd.support;

import com.idfinance.financial_transactions.config.LedgerPolicy;
import com.idfinance.financial_transactions.domain.ExchangeRate;
import com.idfinance.financial_transactions.service.RatesProvider;
import java.time.LocalDate;

/**
 * A rates provider that answers from a scenario's instructions instead of a
 * network.
 *
 * <p>
 * Two of the three answers are refusals, and that is the point.
 * {@link Mode#UNKNOWN} returns no rate for anything but the ledger currency,
 * which is what sends a transaction in a real currency down the full lookup
 * chain and leaves it stored without a flag. {@link Mode#FAILING} throws, which
 * is what a provider outage looks like to {@code ExchangeRateService} and
 * therefore what must not fail the ingestion path.
 *
 * <p>
 * The ledger currency is read from {@link LedgerPolicy} rather than spelled
 * out, so a stub that hardcoded {@code USD} would pass today and stop passing
 * the day the ledger moved.
 */
public class StubRatesProvider implements RatesProvider {

	private final LedgerPolicy ledgerPolicy;

	private volatile Mode mode = Mode.UNKNOWN;

	public StubRatesProvider(LedgerPolicy ledgerPolicy) {
		this.ledgerPolicy = ledgerPolicy;
	}

	public void setMode(Mode mode) {
		this.mode = mode;
	}

	@Override
	public ExchangeRate rateFor(String baseCurrency, String quoteCurrency, LocalDate date) {
		if (this.ledgerPolicy.isLedgerCurrency(baseCurrency)) {
			return ExchangeRate.identity(baseCurrency, date);
		}
		if (this.mode == Mode.FAILING) {
			throw new IllegalStateException("The rates provider is unreachable");
		}
		return null;
	}

	@Override
	public String name() {
		return "stub";
	}

	/**
	 * What the provider does with a currency it has no rate for.
	 */
	public enum Mode {

		/** No rate at all, which sends the caller through the whole lookup chain. */
		UNKNOWN,

		/** Refuses outright, as an unreachable provider would. */
		FAILING

	}

}
