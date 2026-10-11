package com.idfinance.financial_transactions.bdd.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.idfinance.financial_transactions.dto.TransactionRequest;
import com.idfinance.financial_transactions.dto.TransactionWithLimitResponse;
import com.idfinance.financial_transactions.web.WireNames;
import io.cucumber.java.en.Then;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/**
 * The wire contract, checked against the specification's two tables.
 *
 * <p>
 * Two things are checked and the second is the reason this exists. Jackson does
 * the renaming on the way out; the violation handler renames a field on the way
 * into the error message. That is two implementations of one rule, and their
 * failure mode is not a crash — it is a 400 naming a field that is not in the
 * request.
 *
 * <p>
 * Jackson's own answer is therefore read by serialising a real payload and
 * looking at the keys, rather than by asserting what it ought to do. Checking
 * it against a list of names written here would only prove the list agrees with
 * itself.
 */
class WireContractSteps {

	private final ObjectMapper mapper = new ObjectMapper();

	@Then("the transaction request answers to these names on the wire")
	void theRequestNamesAreTheSpecifications() {
		assertThat(wireNamesOf(TransactionRequest.class)).as("the six fields the specification fixes for a transaction")
				.containsExactly("account_from", "account_to", "currency_shortname", "sum", "expense_category",
						"datetime");
	}

	/**
	 * The three fields requirement 6 adds, and the two this service adds of its
	 * own.
	 *
	 * <p>
	 * Asserted against the whole component list rather than by looking three of
	 * them up, because a response is a contract too: a scenario checking only the
	 * three would happily accept any number of others.
	 *
	 * <p>
	 * {@code limit_exceeded} is in the list because requirement 4 asks for the flag
	 * and a client cannot use a flag it cannot read, though requirement 6's table
	 * names only three additions. {@code sum_in_ledger} is this service's own — the
	 * converted amount, which the document asks to be stored rather than returned.
	 */
	@Then("the response adds the specification's three fields")
	void theResponseAddsThreeFields() {
		assertThat(wireNamesOf(TransactionWithLimitResponse.class))
				.as("the transaction, the three fields of requirement 6, and the flag of requirement 4")
				.containsExactly("account_from", "account_to", "currency_shortname", "sum", "expense_category",
						"datetime", "sum_in_ledger", "limit_exceeded", "limit_sum", "limit_datetime",
						"limit_currency_shortname");
	}

	/**
	 * The two implementations of the naming rule, compared for real.
	 *
	 * <p>
	 * One JSON object read out of Jackson and one list of names the violation
	 * handler would produce, from the same components. They have to be the same
	 * list: a 400 that named {@code accountFrom} while the body said
	 * {@code account_from} would send a caller looking for a field they never sent.
	 */
	@Then("a violation names every field the way Jackson writes it")
	void aViolationNamesEveryFieldTheWayJacksonWritesIt() {
		Map<String, String> sample = Map.of("accountFrom", "0000000123", "accountTo", "9999999999", "currencyShortname",
				"KZT", "sum", "10.00", "expenseCategory", "product", "datetime", "2022-01-05T00:00:00+00:00");
		TransactionRequest payload = new TransactionRequest(sample.get("accountFrom"), sample.get("accountTo"),
				sample.get("currencyShortname"), new java.math.BigDecimal(sample.get("sum")),
				sample.get("expenseCategory"), OffsetDateTime.parse(sample.get("datetime")));

		List<String> asJacksonWritesIt = new ArrayList<>(this.mapper.valueToTree(payload).propertyNames());

		assertThat(asJacksonWritesIt.stream().map(WireNames::toWire).toList())
				.as("the names the violation handler would give, against the keys Jackson wrote")
				.containsExactlyElementsOf(asJacksonWritesIt);
	}

	/** The wire names of a record's components, in declaration order. */
	private List<String> wireNamesOf(Class<?> dto) {
		Map<String, String> ordered = new LinkedHashMap<>();
		Arrays.stream(dto.getRecordComponents())
				.forEach((component) -> ordered.put(component.getName(), WireNames.toWire(component.getName())));
		assertThat(ordered).as("%s has no component this scenario could check", dto.getSimpleName()).isNotEmpty();
		return List.copyOf(ordered.values());
	}

}
