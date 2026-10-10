package com.idfinance.financial_transactions.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
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
 *            settings of the external rates provider
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(BigDecimal defaultLimitSum, ZoneId monthTimezone, int scheduledFetchHour, Rates rates) {

	/**
	 * Settings of the external rates provider.
	 *
	 * @param source
	 *            identifier written into every stored rate, so a change of provider
	 *            shows up in the data rather than having to be guessed at
	 * @param apiKey
	 *            provider key, supplied through the environment and never committed
	 * @param connectTimeout
	 *            how long to wait for the connection
	 * @param readTimeout
	 *            how long to wait for the response
	 * @param attempts
	 *            total attempts before the transaction is rejected, at least 1
	 * @param retryBackoff
	 *            pause between attempts
	 * @param fallbackDays
	 *            how far to look in the past
	 */
	public record Rates(String source, String baseUrl, String apiKey, Duration connectTimeout, Duration readTimeout,
			int attempts, Duration retryBackoff, Frankfurter frankfurter, int fallbackDays) {

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
		}

		/**
		 * The fallback provider.
		 *
		 * <p>
		 * No key and no quota, which is why it is a single URL: there is nothing else
		 * to configure about it.
		 *
		 * @param baseUrl
		 *            defaults to the public instance; point it at a self-hosted one to
		 *            keep the requests off a third party
		 */
		public record Frankfurter(String baseUrl) {
		}

	}

}
