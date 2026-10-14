package com.idfinance.financial_transactions.bdd.support;

import com.idfinance.financial_transactions.config.LedgerPolicy;
import com.idfinance.financial_transactions.domain.ExchangeRate;
import com.idfinance.financial_transactions.service.RatesProvider;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * A rates provider that answers from a scenario's instructions instead of a
 * network.
 *
 * <p>
 * {@link Mode#UNKNOWN} returns no rate for anything but the ledger currency,
 * which is what sends a transaction in a real currency down the full lookup
 * chain and leaves it stored without a flag. {@link Mode#FAILING} throws, which
 * is what a provider outage looks like to {@code ExchangeRateService} and
 * therefore what must not fail the ingestion path. {@link Mode#ANSWERING}
 * quotes whatever it is asked for, which is what an up provider does and what a
 * scenario asserting the sweep's own behaviour needs: everything else runs
 * against a stub that says "no", so the daily job would store nothing and could
 * not be observed.
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

	private int rateCalls;

	/**
	 * What {@link Mode#ANSWERING} quotes.
	 *
	 * <p>
	 * A plausible close rather than a chosen one: the scenarios that set this mode
	 * assert which <em>day</em> a rate was stored for and whether the provider was
	 * asked at all, never an amount. Digits are picked so a realistic sum converts
	 * into something the ledger column holds, and it is a rate below one because
	 * that is what a quote of a foreign currency into the ledger is.
	 */
	private static final BigDecimal ANSWERED = new BigDecimal("0.00222");

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
	public Optional<ExchangeRate> rateFor(String baseCurrency, String quoteCurrency, LocalDate date) {
		// Counted on entry rather than on a successful answer: a scenario asserting
		// that
		// the caller never reached the provider is asserting this was not entered, and
		// an entry that then threw still cost the round trip.
		this.rateCalls++;
		if (this.ledgerPolicy.isLedgerCurrency(baseCurrency)) {
			return Optional.of(ExchangeRate.identity(baseCurrency, date));
		}
		if (this.mode == Mode.FAILING) {
			throw new IllegalStateException("The rates provider is unreachable");
		}
		if (this.mode == Mode.ANSWERING) {
			return Optional.of(new ExchangeRate(baseCurrency, quoteCurrency, date, ANSWERED, null, name()));
		}
		return Optional.empty();
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

	/**
	 * How many times {@link #rateFor} was entered.
	 *
	 * <p>
	 * The assertion for anything the service is supposed to answer out of its own
	 * table. A rate that should have been stored but was not costs a call that a
	 * scenario reading only the stored row would never see, because the transaction
	 * is stored either way — with the flag left uncalculated, which looks like a
	 * missing rate rather than a missed sweep.
	 */
	public int rateCalls() {
		return this.rateCalls;
	}

	public void resetRateCalls() {
		this.rateCalls = 0;
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
		FAILING,

		/**
		 * Quotes every day it is asked about, which is what an up provider does.
		 *
		 * <p>
		 * Needed by the daily sweep's scenarios and by nothing else: with the default
		 * {@link #UNKNOWN} the job stores nothing, so "the sweep wrote a row dated
		 * yesterday" has no row to be about and the assertion fails for a reason that
		 * has nothing to do with the date it was checking.
		 */
		ANSWERING

	}

}
