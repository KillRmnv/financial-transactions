package com.idfinance.financial_transactions.exception;

/**
 * Referenced data the request depends on does not exist: an unknown category
 * code or an account that has never been seen.
 */
public class NotFoundException extends DomainException {

	public NotFoundException(String message) {
		super(message, "not-found");
	}

	public static NotFoundException category(String code) {
		return new NotFoundException("Unknown expense category '%s'; expected one of product, service".formatted(code));
	}

	/**
	 * The currency is not in the lookup, so the request cannot be honoured. The
	 * message names the spelling that was rejected, because "kzt" versus "KZT" is
	 * the mistake this guards against.
	 */
	public static NotFoundException currency(String code) {
		return new NotFoundException(
				"Unknown currency '%s'; see /api/v1/currencies for the supported codes".formatted(code));
	}

}
