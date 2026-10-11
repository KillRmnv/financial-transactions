package com.idfinance.financial_transactions.logging;

import java.util.regex.Pattern;

/**
 * What may be written to a log, and what may not.
 *
 * <p>
 * A log is the one place in this service where data about a client is written
 * down somewhere that is not the database: it goes to a file, then to an
 * aggregator, and every operator and every third party with a log query can
 * read it. Nothing that identifies a client, authorises anything or describes
 * the inside of the deployment belongs there, and the places where that used to
 * happen were all of them ordinary enough to look harmless.
 *
 * <p>
 * The three that mattered, in order of how bad they were:
 *
 * <ul>
 * <li><b>The provider's API key.</b> Twelve Data is asked with the key as a
 * query parameter, and a {@code RestClientResponseException} carries the whole
 * URI in its message — so every 404 from the paid provider put a working
 * credential into a log at {@code warn}. It is the credential the specification
 * says the whole paid path hangs on, and reading it back out of a log file is
 * enough to spend money.
 * <li><b>Account numbers.</b> Ten digits, unique to a client, printed at
 * {@code info} on every ingestion and every limit installed.
 * <li><b>The caller's address.</b> Logged when a quota is refused, which is the
 * one place it is most likely to be read by someone curious about who is being
 * throttled.
 * </ul>
 *
 * <p>
 * What is left is what the service is operated on: currency codes, rates,
 * dates, counts, outcomes and what kind of thing failed. None of that
 * identifies a client, and all of it is what makes a log worth reading.
 *
 * <p>
 * {@link #failure} is the one to reach for rather than {@code ex.getMessage()}.
 * The failure itself still propagates untouched — this only decides what is
 * written down.
 */
public final class SafeLog {

	/** Query parameters worth redacting wherever they appear, not only in a URL. */
	private static final Pattern CREDENTIAL = Pattern
			.compile("(?i)\\b(api[-_]?key|token|password|secret|authorization|bearer)\\s*[=:]\\s*[^\\s&,;\"'}\\]]+");

	/** Any URL, so that a query string can be dropped from it whole. */
	private static final Pattern URL = Pattern.compile("(?i)https?://[^\\s\"'<>)\\]]+");

	private static final String REDACTED = "***";

	private SafeLog() {
	}

	/**
	 * An account number reduced to its last four digits.
	 *
	 * <p>
	 * Enough to correlate a log line with a row when something has gone wrong,
	 * which is the whole reason to log it at all, and not enough to be an account.
	 * A ten-digit string printed in full is not a shortened identifier, it is the
	 * identifier.
	 *
	 * @return the last four digits behind a fixed run of stars, or {@code "***"}
	 *         when the value is too short to be one of ours and masking it would
	 *         leave almost nothing to correlate
	 */
	public static String account(String accountFrom) {
		if (accountFrom == null || accountFrom.isBlank()) {
			return REDACTED;
		}
		int visible = Math.min(4, accountFrom.length());
		return "*".repeat(accountFrom.length() - visible) + accountFrom.substring(accountFrom.length() - visible);
	}

	/**
	 * A caller's address, reduced to its last octet.
	 *
	 * <p>
	 * Kept rather than dropped for the same reason an account is: a throttled
	 * caller is usually one caller repeating itself, and there is no way to see
	 * that without something to group by. What is deliberately not there is the
	 * rest of the address, which is both a way to identify a person and a
	 * description of the network the service is deployed into.
	 */
	public static String remoteAddress(String address) {
		if (address == null || address.isBlank()) {
			return REDACTED;
		}
		int lastDot = address.lastIndexOf('.');
		return (lastDot < 0) ? REDACTED : "***.***.***." + address.substring(lastDot + 1);
	}

	/**
	 * A throwable's message, safe to log.
	 *
	 * <p>
	 * The type is kept because it is the part that says what went wrong, and the
	 * message is kept because a message that is scrubbed to nothing is worse than
	 * none — an operator sees the class and still knows where to look. The query
	 * string goes as a whole: the symbols and dates in it are already logged
	 * separately, and the one parameter that must never survive is the key.
	 */
	public static String failure(Throwable throwable) {
		if (throwable == null) {
			return "nothing was thrown";
		}
		String message = throwable.getMessage();
		if (message == null || message.isBlank()) {
			return throwable.getClass().getSimpleName();
		}
		return scrub(message);
	}

	/**
	 * Removes credentials and query strings from a string.
	 *
	 * <p>
	 * Public because a provider's response body goes through the same treatment as
	 * an exception message: it is third-party text we do not control, and a
	 * provider that echoes the key back in an error body is answering with our
	 * credential.
	 */
	public static String scrub(String text) {
		if (text == null) {
			return "";
		}
		String withoutCredentials = CREDENTIAL.matcher(text).replaceAll(match -> match.group(1) + "=" + REDACTED);
		return URL.matcher(withoutCredentials).replaceAll(
				match -> match.group().split("\\?", 2)[0] + (match.group().contains("?") ? "?" + REDACTED : ""));
	}

}
