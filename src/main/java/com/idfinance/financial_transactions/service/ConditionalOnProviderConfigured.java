package com.idfinance.financial_transactions.service;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Registers a class only while its own rates provider is configured.
 *
 * <p>
 * The value is the provider's key under {@code app.rates.providers}, which is
 * the only place in the code that key has to appear: the annotation on the
 * adapter, and the block in {@code application.yml}. The class therefore states
 * its own configuration and knows nothing about anybody else's.
 *
 * <p>
 * This replaces
 * {@code @ConditionalOnProperty(name = "app.rates.<provider>.base-url")}, which
 * matched on a property merely being present. That is a different question: a
 * suite blanking the URL leaves the property present with an empty value, the
 * annotation matched, and the adapter was constructed with nothing to call. The
 * question here is whether the value is there, which is what "configured"
 * means.
 *
 * <p>
 * A blank URL is how a provider is switched off — no deletion of its block, no
 * second flag that can disagree with the first — and the adapter is absent
 * rather than broken, so the chain and {@code NoRatesProvider} agree about who
 * is answering.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Conditional(ProviderConfiguredCondition.class)
public @interface ConditionalOnProviderConfigured {

	/** The provider's key under {@code app.rates.providers}. */
	String value();

}

/**
 * The condition itself, kept apart from the annotation so the annotation
 * carries no logic and the logic names no provider.
 */
final class ProviderConfiguredCondition extends SpringBootCondition {

	/**
	 * Whether any provider may register at all.
	 *
	 * <p>
	 * The URL is what says whether a provider is configured, but the suites need a
	 * way to say "no external provider at all" without listing every provider's
	 * property — and that list is exactly the duplication this configuration was
	 * reshaped to remove. A test cannot remove a key from {@code application.yml},
	 * only add one, so {@code app.rates.providers={}} leaves the nested URLs
	 * beneath it untouched and every adapter registers anyway.
	 *
	 * <p>
	 * A master switch rather than a second flag that can disagree: with it off, no
	 * block registers whatever it says, so the two cannot contradict each other. It
	 * exists because a suite needs it, and a test-only reason is a good enough
	 * reason for a property that changes nothing in a deployment.
	 */
	private static final String MASTER_SWITCH = "app.rates.providers-enabled";

	private static boolean isDisabled(ConditionContext context) {
		return !Boolean.parseBoolean(context.getEnvironment().getProperty(MASTER_SWITCH, "true"));
	}

	@Override
	public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
		if (isDisabled(context)) {
			return ConditionOutcome.noMatch(ConditionMessage.forCondition(ConditionalOnProviderConfigured.class)
					.didNotFind("a provider, because " + MASTER_SWITCH + " is off").atAll());
		}
		Map<String, Object> attributes = metadata
				.getAnnotationAttributes(ConditionalOnProviderConfigured.class.getName());
		String provider = attributes == null ? null : String.valueOf(attributes.get("value"));
		if (provider == null) {
			return ConditionOutcome.noMatch(ConditionMessage.forCondition(ConditionalOnProviderConfigured.class)
					.didNotFind("a provider name on the annotation").atAll());
		}
		String baseUrl = context.getEnvironment().getProperty("app.rates.providers." + provider + ".base-url");
		if (baseUrl == null || baseUrl.isBlank()) {
			return ConditionOutcome.noMatch(ConditionMessage.forCondition(ConditionalOnProviderConfigured.class)
					.didNotFind("a base-url for provider " + provider).atAll());
		}
		return ConditionOutcome.match(
				ConditionMessage.forCondition(ConditionalOnProviderConfigured.class).found("base-url").items(baseUrl));
	}

}
