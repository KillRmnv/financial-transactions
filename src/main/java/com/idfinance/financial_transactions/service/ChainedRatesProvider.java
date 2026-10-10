package com.idfinance.financial_transactions.service;

import com.idfinance.financial_transactions.config.AppProperties;
import com.idfinance.financial_transactions.domain.ExchangeRate;
import com.idfinance.financial_transactions.domain.ExchangeRate;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Asks the configured providers in order, and answers with the first that has
 * something to say.
 *
 * <p>
 * Lives in {@code service} rather than in {@code client} because choosing
 * between sources is a policy, not HTTP: the adapters each speak to one
 * provider, and deciding which one is asked first is the deployment's decision.
 * It is in {@code service} and not in {@code client} for the same reason
 * {@code RatesProvider} is.
 *
 * <p>
 * Conditional on at least one provider being configured, which is what keeps it
 * out of the way in the suites that stub {@code RatesProvider} in-process. A
 * composite over a stub has nothing to fall back to, and marking both it and
 * the stub as {@code @Primary} is a context that refuses to start. The stub
 * contexts therefore configure no provider at all rather than this bean being
 * conditionally absent by some subtler means.
 *
 * <p>
 * The order is deliberately significant and is a property of the deployment,
 * not of the code: each provider carries its own {@code order} in its block,
 * and they are asked in ascending order. A deployment that wants the free
 * provider asked first swaps two numbers, and a new provider slots between
 * existing ones without any neighbour being renumbered — a paid provider
 * silently demoted to second costs money in a direction nobody chose, so the
 * sequence is configuration and not a fact about the classes.
 *
 * <p>
 * The first answer wins, whatever kind it was: a rate, or an absence of one.
 * Falling through on an absence is the point. The paid provider's free tier
 * reaches back a year and leaves weekends empty, so a transaction older than
 * that, or one dated on a Saturday, has no answer from it — and an ingestion
 * path that stopped there would store a transaction it could have priced.
 *
 * <p>
 * Which provider answered is carried on the rate itself
 * ({@link ExchangeRate#source()}) rather than asked for here, because that is
 * the only way {@code currency_rate.source} can name the one that actually
 * supplied the number.
 *
 * <p>
 * This class implements {@link RatesProvider} because everything that consumes
 * rates is written against that type, and the set of implementations is
 * conditional: a payment-less deployment runs {@code NoRatesProvider}, the
 * suites run in-process stubs, and this class only exists once at least one
 * provider is configured. Dropping it in favour of a concrete class would make
 * every consumer require a URL to be set before it would start.
 *
 * <p>
 * The condition is {@link ProvidersConfigured} rather than an expression over
 * the property names, for the reason that class exists: an expression would
 * have to list every provider, so adding one would mean editing this class, and
 * forgetting to would register an adapter nobody ever calls.
 *
 * <p>
 * For the same reason it injects {@code ObjectProvider<RatesProvider>} rather
 * than a {@code List<RatesProvider>} — as a primary bean of that very type, a
 * plain list would hand it itself, and it would ask itself first. The reference
 * is dropped by identity rather than by a marker interface: an
 * {@code ExternalRatesProvider} sub-interface would exist only to narrow a
 * list, and every adapter would have to declare it.
 *
 * <p>
 * A transport failure does not fall through. The caller already treats that as
 * "store it without a flag", and retrying it against the next provider would
 * turn one outage into two requests and then the same answer. Which failures
 * are which is the adapter's business: a 404 for a pair the provider does not
 * publish comes back as null, not as an exception.
 */
@Component
@ConditionalOnBean(AppProperties.class)
@Conditional(ProvidersConfigured.class)
@Primary
@Slf4j
public final class ChainedRatesProvider implements RatesProvider {

	private final ObjectProvider<RatesProvider> others;

	private final AppProperties properties;

	public ChainedRatesProvider(AppProperties properties, ObjectProvider<RatesProvider> others) {
		this.properties = properties;
		this.others = others;
	}

	@Override
	public String name() {
		return this.head().name();
	}

	@Override
	public ExchangeRate rateFor(String baseCurrency, String quoteCurrency, LocalDate date) {
		for (RatesProvider provider : this.chain()) {
			ExchangeRate rate = provider.rateFor(baseCurrency, quoteCurrency, date);
			if (rate != null) {
				log.debug("{} answered {} for {} on {}", rate.source(), rate.close(),
						baseCurrency + "/" + quoteCurrency, date);
				return rate;
			}
		}

		log.warn("No provider has a rate for {} on {}", baseCurrency + "/" + quoteCurrency, date);
		return null;
	}

	/**
	 * The first provider that publishes a list, rather than a merge of all of them.
	 *
	 * <p>
	 * A union would be wrong in the one direction that matters: the free provider
	 * carries about eighty retired codes behind a filter, and the paid one's list
	 * is narrower. Taking one provider's view keeps the lookup a statement about a
	 * single source.
	 */
	@Override
	public List<RatesProvider.PublishedCurrency> publishedCurrencies() {
		for (RatesProvider provider : this.chain()) {
			List<RatesProvider.PublishedCurrency> published = provider.publishedCurrencies();
			if (!published.isEmpty()) {
				return published;
			}
		}
		return List.of();
	}

	/**
	 * Who is asked first. The order is a property of the deployment rather than of
	 * the classes: it lives in the provider's own block under
	 * {@code app.rates.providers}, so an operator who wants the free provider asked
	 * first swaps two numbers, and a new provider slots between existing ones
	 * without any neighbour being renumbered.
	 */
	private RatesProvider head() {
		return this.chain().get(0);
	}

	/**
	 * Every other {@code RatesProvider} in the context, in the order its block asks
	 * for, without this one.
	 *
	 * <p>
	 * Sorted by the provider's own {@code order} rather than by
	 * {@code ObjectProvider.orderedStream()}, because the sequence is deployment
	 * configuration and not a fact about the class. A provider with no entry — a
	 * stub, in a suite — sorts last, which is what a bean nobody configured should
	 * do.
	 *
	 * <p>
	 * Dropping the bean that is asking is by reference identity: the alternative is
	 * a sub-interface that exists for nothing but narrowing a list, which is a type
	 * added to every adapter to solve one injection point.
	 */
	private List<RatesProvider> chain() {
		Map<String, Integer> orders = new java.util.HashMap<>();
		// Keyed by the name a provider answers to, not by the key it was configured
		// under: a block may override its name — a sandbox deployment writes
		// `twelvedata-sandbox` into currency_rate.source — and a map keyed by the
		// configuration key would then miss every overridden entry and sort them all
		// last, which puts the paid provider behind the free one for no visible
		// reason.
		this.properties.rates().providers().forEach((key, provider) -> orders.put(provider.name(), provider.order()));
		return this.others.orderedStream().filter(provider -> provider != this).sorted(
				java.util.Comparator.comparingInt(provider -> orders.getOrDefault(provider.name(), Integer.MAX_VALUE)))
				.toList();
	}

}
