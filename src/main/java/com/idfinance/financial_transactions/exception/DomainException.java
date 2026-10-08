package com.idfinance.financial_transactions.exception;

/**
 * Base class for the exceptions this service raises deliberately.
 *
 * <p>
 * Services throw these; the single exception handler in the {@code exception}
 * package is what turns them into an HTTP status. Nothing outside the handler
 * should know about {@code HttpStatus}.
 */
public abstract class DomainException extends RuntimeException {

	private final String typeSuffix;

	protected DomainException(String message, String typeSuffix) {
		super(message);
		this.typeSuffix = typeSuffix;
	}

	protected DomainException(String message, String typeSuffix, Throwable cause) {
		super(message, cause);
		this.typeSuffix = typeSuffix;
	}

	/**
	 * @return last segment of the RFC 9457 {@code type} URN, for example
	 *         {@code rate-unavailable}
	 */
	public String typeSuffix() {
		return this.typeSuffix;
	}

}
