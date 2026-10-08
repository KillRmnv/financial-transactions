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
	 */
	public record Rates(String source, String baseUrl, String apiKey, Duration connectTimeout, Duration readTimeout,
			int attempts, Duration retryBackoff) {
	}

}
