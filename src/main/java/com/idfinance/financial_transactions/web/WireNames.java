package com.idfinance.financial_transactions.web;

import java.util.Locale;

/**
 * The name a field carries on the wire, given the name it has in Java.
 *
 * <p>
 * Two places need this answer and neither can ask Jackson for it. Serialisation
 * asks it through {@code @JsonNaming}, which is the real mechanism and needs no
 * help. A validation violation needs it too, because Bean Validation knows only
 * the record component — {@code accountFrom} — and reporting that would point
 * the caller at a field that does not exist in the request it sent. Jackson's
 * own translator is protected and its public methods all want a
 * {@code MapperConfig} and an introspected field, which is a great deal of
 * machinery to spell a camelCase name.
 *
 * <p>
 * So this is the second copy of the rule, and the reason it is safe is not that
 * it is short but that it is checked:
 * {@code features/rule/wire_contract.feature} reflects over the DTOs and
 * compares what this class says against the specification's tables. A component
 * renamed without the contract being reconsidered fails there rather than
 * producing an error that names a field nobody sent.
 *
 * <p>
 * Deliberately the same algorithm as Jackson's snake_case, and not a near miss:
 * a divergence here would show up as a violation naming something subtly
 * different from the field that failed, which is the hardest kind of error
 * message to read.
 */
public final class WireNames {

	private WireNames() {
	}

	/**
	 * The wire name of a Java field name.
	 *
	 * <p>
	 * A run of capitals is a boundary and the capital after it is lowercased, so
	 * {@code limitCurrencyShortname} becomes {@code limit_currency_shortname}
	 * rather than {@code limit_currency_s_h_o_r_t_n_a_m_e}. That is the same rule
	 * Jackson applies, and it is the rule that keeps an acronym readable.
	 */
	public static String toWire(String fieldName) {
		StringBuilder wire = new StringBuilder(fieldName.length() + 4);
		for (int index = 0; index < fieldName.length(); index++) {
			char character = fieldName.charAt(index);
			if (Character.isUpperCase(character)) {
				boolean startsWord = index > 0 && !Character.isUpperCase(fieldName.charAt(index - 1));
				if (startsWord) {
					wire.append('_');
				}
				wire.append(Character.toLowerCase(character));
			} else {
				wire.append(character);
			}
		}
		return wire.toString().toLowerCase(Locale.ROOT);
	}

}
