package com.idfinance.financial_transactions.bdd.support;

import com.idfinance.financial_transactions.config.LedgerPolicy;
import com.idfinance.financial_transactions.domain.ExchangeRate;
import com.idfinance.financial_transactions.service.RatesProvider;
import java.time.LocalDate;
import java.util.List;

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
 *
 * <p>
 * The published-currency listing is a third answer, set per scenario, because
 * {@code WeeklyCurrencyImport} reads it and nothing else does.
 */
public class StubRatesProvider implements RatesProvider {

	private final LedgerPolicy ledgerPolicy;

	private volatile Mode mode = Mode.UNKNOWN;

	private volatile List<PublishedCurrency> published = List.of();

	private volatile boolean currencyListingFails;

	private int currencyListingCalls;

	public StubRatesProvider(LedgerPolicy ledgerPolicy) {
		this.ledgerPolicy = ledgerPolicy;
	}

	public void setMode(Mode mode) {
		this.mode = mode;
	}

	/**
	 * What {@link #publishedCurrencies()} will answer. The weekly sweep of the
	 * currency lookup reads nothing else, so a scenario that wants to see it work
	 * says here which codes the provider claims to publish.
	 */
	public void setPublishedCurrencies(PublishedCurrency... currencies) {
		this.published = List.of(currencies);
		this.currencyListingFails = false;
	}

	/**
	 * Makes the currency listing throw, which is what a provider outage looks like
	 * to the sweep. It has to be a distinct switch from {@link Mode#FAILING}: the
	 * rate lookup and the currency listing fail independently, and a scenario that
	 * covers one must not accidentally be covering the other.
	 */
	public void setCurrencyListingFails(boolean fails) {
		this.currencyListingFails = fails;
		this.published = List.of();
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
	public List<PublishedCurrency> publishedCurrencies() {
		this.currencyListingCalls++;
		if (this.currencyListingFails) {
			throw new IllegalStateException("The rates provider is unreachable");
		}
		return this.published;
	}

	/**
	 * How many times the currency list was asked for.
	 *
	 * <p>
	 * A job that is switched off must be asserted by the call <em>not</em> being
	 * made rather than by the lookup not growing: with the list left empty the
	 * lookup would not grow either way, and the scenario would pass against an
	 * implementation that simply paid the credits anyway.
	 */
	public int currencyListingCalls() {
		return this.currencyListingCalls;
	}

	public void resetCurrencyListingCalls() {
		this.currencyListingCalls = 0;
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
