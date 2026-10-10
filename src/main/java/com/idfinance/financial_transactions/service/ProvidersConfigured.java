package com.idfinance.financial_transactions.service;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches when at least one rates provider is configured, whichever it is.
 *
 * <p>
 * This replaces the expression that used to name every provider's property:
 *
 * <pre>
 * "'${app.rates.base-url:}' != '' or '${app.rates.frankfurter.base-url:}' != ''"
 * </pre>
 *
 * That form was the one place adding a provider silently did not work. A third
 * provider was configured, its adapter registered itself, and it was never
 * asked, because nobody had added its property to a boolean expression on a
 * class that has nothing to do with it. The failure is invisible: nothing
 * refuses to start, the rate simply never resolves, and the transaction is
 * stored without a flag.
 *
 * <p>
 * The question here is about the set, not about its members — "is anything
 * configured" — so it stays true when a provider is added and needs no edit.
 * The individual adapters keep their own conditions, which name their own
 * block, and that is as it should be: a class stating its own configuration is
 * local knowledge, whereas a class stating every other class's is a coupling
 * that grows with the number of providers.
 *
 * <p>
 * Read from the {@code Environment} rather than from the bound
 * {@code AppProperties}: a condition on a scanned component runs while the
 * component is being listed, which is before the configuration-properties bean
 * exists, and asking for it there is a {@code NoSuchBeanDefinitionException}
 * that reads as a bug in the condition rather than as an ordering fact.
 *
 * <p>
 * Providers are found by <em>enumerating</em>
 * {@code app.rates.providers.*.base-url} rather than by reading a property
 * named {@code app.rates.providers}. The map has no value of its own, only
 * entries, so asking for the container key answers null for every configuration
 * that actually works — which leaves the chain absent and two bare adapters
 * fighting over the {@code RatesProvider} injection point, and the context
 * refuses to start.
 */
public final class ProvidersConfigured extends SpringBootCondition {

	/** The block every provider lives in, and the property that registers one. */
	private static final String PREFIX = "app.rates.providers.";

	private static final String BASE_URL = ".base-url";

	@Override
	public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
		if (!Boolean.parseBoolean(context.getEnvironment().getProperty("app.rates.providers-enabled", "true"))) {
			return ConditionOutcome.noMatch(ConditionMessage.forCondition("ProvidersConfigured")
					.didNotFind("a provider, because app.rates.providers-enabled is off").atAll());
		}
		List<String> configured = new ArrayList<>();
		for (PropertySource<?> source : configurable(context).getPropertySources()) {
			if (source instanceof EnumerablePropertySource<?> enumerable) {
				for (String key : enumerable.getPropertyNames()) {
					if (isConfiguredProvider(key, source.getProperty(key))) {
						configured.add(key.substring(PREFIX.length(), key.length() - BASE_URL.length()));
					}
				}
			}
		}

		if (configured.isEmpty()) {
			return ConditionOutcome.noMatch(ConditionMessage.forCondition("ProvidersConfigured")
					.didNotFind("a base-url under " + PREFIX).atAll());
		}
		return ConditionOutcome
				.match(ConditionMessage.forCondition("ProvidersConfigured").found("providers").items(configured));
	}

	/** Whether this property is a provider's URL, and whether it has a value. */
	private static boolean isConfiguredProvider(String key, Object value) {
		if (key == null || !key.startsWith(PREFIX) || !key.endsWith(BASE_URL)) {
			return false;
		}
		return value != null && !value.toString().isBlank();
	}

	/**
	 * The environment with its sources enumerated.
	 *
	 * <p>
	 * A {@link ConditionContext} hands out an
	 * {@link org.springframework.core.env.Environment}, which has no way to list
	 * what is in it — asking a name works, which is what asking a name does not.
	 * Every context Spring builds is configurable, so this is a cast rather than a
	 * branch; a fallback to an empty list would turn an unexpected environment into
	 * "no providers", which is the silent failure this class exists to remove.
	 */
	private static ConfigurableEnvironment configurable(ConditionContext context) {
		return (ConfigurableEnvironment) context.getEnvironment();
	}

}
