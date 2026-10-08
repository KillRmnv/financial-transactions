package com.idfinance.financial_transactions.exception;

/**
 * An external dependency could not be reached, or answered with an error, after
 * all configured attempts were exhausted.
 *
 * <p>
 * Named for the failure rather than for the dependency: a future provider of
 * exchange rates, fraud scoring or anything else reuses this exception and only
 * supplies its own name and message. Pinning the name to rates today would mean
 * a rename the moment a second integration arrives.
 */
public class ExternalServiceUnavailableException extends DomainException {

	private final String service;

	public ExternalServiceUnavailableException(String service, String what, Throwable cause) {
		super("The %s service did not answer while %s".formatted(service, what), "external-service-unavailable", cause);
		this.service = service;
	}

	public ExternalServiceUnavailableException(String service, String what) {
		super("The %s service did not answer while %s".formatted(service, what), "external-service-unavailable");
		this.service = service;
	}

	/** @return the dependency that failed, for logs and metrics */
	public String service() {
		return this.service;
	}

}
