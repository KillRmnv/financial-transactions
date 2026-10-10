package com.idfinance.financial_transactions.bdd.support;

/**
 * The port the rates WireMock listens on, in one place.
 *
 * <p>
 * Its own class because an annotation attribute has to be a constant
 * expression, and a constant declared in the class the annotation sits on is
 * not visible to it: the annotation is read while the class is being declared.
 * Keeping the number here means the server and the contexts that point at it
 * read one value.
 */
public final class WireMockPort {

	/**
	 * Nothing outside the test JVM can reach it, so a fixed port is safe and
	 * simpler.
	 */
	public static final int VALUE = 18080;

	private WireMockPort() {
	}

}
