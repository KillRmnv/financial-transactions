package com.idfinance.financial_transactions.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * External configuration bound to the {@code app.*} prefix, so that no URL,
 * credential, timezone or business default is hardcoded in the service layer.
 *
 * @param defaultLimitSum
 *            limit applied when the client never set one, in USD
 * @param monthTimezone
 *            the single timezone in which month boundaries are computed
 * @param scheduledFetchHour
 *            hour of the daily refresh, in {@code monthTimezone}
 * @param rates
 *            settings shared by every provider, and the providers themselves
 * @param limits
 *            which rule measures the month, and what each category counts as
 *            against its limit
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(BigDecimal defaultLimitSum, ZoneId monthTimezone, int scheduledFetchHour, Rates rates,
		Limits limits) {

	/**
	 * Which rule measures a limit, and the weights that rule uses.
	 *
	 * <p>
	 * {@code policy} is a name rather than a flag, because it names one of the
	 * rules in {@code domain} and the two are told apart by which one each answers
	 * to: adding a third rule means a third name here, and a mismatch between this
	 * value and a rule's name means no rule is registered at all.
	 *
	 * <p>
	 * {@code weights} is read by the weighted rule and by nothing else — under
	 * {@code monthly-sum} every transaction counts for its full value whatever this
	 * map says, so that the stored weight and the compared weight cannot come from
	 * two places that disagree.
	 */
	public record Limits(String policy, Map<String, BigDecimal> weights) {

		/**
		 * Refuses a weight that cannot mean what a weight has to mean.
		 *
		 * <p>
		 * Zero would say a category's rows are inside the month's total and contribute
		 * nothing to it — two statements about one number, with no way to tell from the
		 * data which was meant. Exempting a category is a decision with a name, and if
		 * that decision is ever wanted it belongs on the transaction rather than in a
		 * multiplier. A negative weight is worse than either: it would let spending
		 * bring a total back down, and the flag would stop meaning what it says.
		 *
		 * <p>
		 * Same reasoning as {@code LedgerPolicy} and {@code RateCachePolicy}, and the
		 * same trap as their checks: in a compact record constructor the fields are not
		 * assigned yet, so this reads the parameters.
		 */
		public Limits {
			Map<String, BigDecimal> copied = (weights == null) ? Map.of() : Map.copyOf(weights);
			copied.forEach((category, weight) -> {
				if (weight == null || weight.signum() <= 0) {
					throw new IllegalArgumentException(
							"app.limits.weights." + category + " must be greater than zero, was: " + weight);
				}
			});
			weights = copied;
		}

		/** What a transaction of this category counts as, its full value by default. */
		public BigDecimal weightOf(String categoryCode) {
			return this.weights.getOrDefault(categoryCode, BigDecimal.ONE);
		}

	}

	/**
	 * Settings that concern every provider, plus the providers themselves.
	 *
	 * <p>
	 * Nothing in here belongs to one provider. A provider's URL, key and name live
	 * in its own block under {@code providers}, and the numbers that govern how any
	 * provider is called — how long it may take, how many attempts, how far back
	 * the paid one looks for the last close before a day with none — sit here
	 * because they are one policy across all of them.
	 *
	 * <p>
	 * The split is the whole reason this is a map. Adding a provider used to mean a
	 * new field or a new nested record here, a new branch in the conditional on the
	 * chain, a new pair of properties to blank in every suite, and a longer
	 * positional argument list in the test configuration. Now it means one block in
	 * {@code application.yml} with a key that appears nowhere else, and a class
	 * that reads it.
	 */
	public record Rates(Duration connectTimeout, Duration readTimeout, int attempts, Duration retryBackoff,
			int fallbackDays, Map<String, Provider> providers) {

		/**
		 * One provider: where it is, what it is called, and how it is spelled.
		 *
		 * <p>
		 * The map key is the name: that is what lands in {@code currency_rate.source}.
		 * An operator who runs a sandbox wants stored rows saying
		 * {@code twelvedata-sandbox} rather than {@code twelvedata}, so {@code source}
		 * overrides it — and when it is absent the key is used, which is why there is
		 * no second place to keep the two in step.
		 *
		 * <p>
		 * {@code api-key} is optional on purpose. A provider without a key can still be
		 * asked — a self-hosted Frankfurter behind a proxy, an internal cache — and one
		 * that refuses an empty key fails as a provider that answers 401, which the
		 * adapter turns into "no rate". Nothing here forces a key that does not belong
		 * to the provider.
		 *
		 * <p>
		 * A blank {@code baseUrl} is not an error: it means the provider is not
		 * configured, which is how one is switched off without deleting its block, and
		 * the condition that registers its adapter simply does not match. A URL that is
		 * present and wrong fails loudly at the first request, which is the right way
		 * to fail: the adapter retries it three times and names the address it could
		 * not reach.
		 *
		 * @param baseUrl
		 *            where the provider is; empty means not configured
		 * @param apiKey
		 *            credential sent as the provider expects it; empty for the ones
		 *            that take none
		 * @param order
		 *            who is asked first, lowest first. Gaps are deliberate: a new
		 *            provider slots between existing ones without renumbering anything,
		 *            and an operator who wants the free one asked first swaps two
		 *            numbers here rather than in code
		 * @param source
		 *            the name written into {@code currency_rate.source}; blank means
		 *            the map key, which is the normal case
		 */
		public record Provider(String baseUrl, String apiKey, int order, String source) {

			/** The same settings, with a blank name replaced by the map key. */
			Provider named(String key) {
				return (this.source == null || this.source.isBlank())
						? new Provider(this.baseUrl, this.apiKey, this.order, key)
						: this;
			}

			/** What is written into {@code currency_rate.source} for a rate from here. */
			public String name() {
				return (this.source == null || this.source.isBlank()) ? "" : this.source;
			}

		}

		/**
		 * Checks the two settings that are meaningless at zero.
		 *
		 * <p>
		 * {@code fallbackDays} is the size of the window the adapter looks back over
		 * when a day has no close of its own: at zero that window is the day itself,
		 * the search finds nothing, and the specification's "price it from the previous
		 * close" never runs — with no error anywhere. {@code attempts} at zero means
		 * the retry policy never attempts at all.
		 *
		 * <p>
		 * Same reasoning as {@code LedgerPolicy} and {@code RateCachePolicy}: a
		 * property that cannot work fails at startup rather than being read as a number
		 * later. The parameters, not the fields, because a compact record constructor
		 * has not assigned them yet.
		 */
		public Rates {
			if (attempts < 1) {
				throw new IllegalArgumentException("app.rates.attempts must be at least 1, was " + attempts);
			}
			if (fallbackDays < 1) {
				throw new IllegalArgumentException("app.rates.fallback-days must be at least 1, was " + fallbackDays);
			}
			// Every entry is stamped with its key, so a provider whose block carries no
			// `source` is still called by that key. Doing it here rather than at each
			// read is what lets `name()` be the single answer to "who is this", which is
			// the same string the chain sorts by and the adapter logs.
			Map<String, Provider> stamped = new java.util.LinkedHashMap<>();
			if (providers != null) {
				providers.forEach((name, provider) -> stamped.put(name, provider.named(name)));
			}
			providers = Map.copyOf(stamped);
		}

	}

}
