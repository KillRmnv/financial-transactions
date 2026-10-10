package com.idfinance.financial_transactions.service;

import com.idfinance.financial_transactions.entity.Currency;
import com.idfinance.financial_transactions.entity.CurrencyPair;
import com.idfinance.financial_transactions.exception.NotFoundException;
import com.idfinance.financial_transactions.repository.CurrencyPairRepository;
import com.idfinance.financial_transactions.repository.CurrencyRepository;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which currencies the daily sweep keeps warm.
 *
 * <p>
 * The set lives in {@code currency_pair} rather than in configuration so an
 * operator can add or retire one without a release. This service is what makes
 * that operable from the application: without it the table could only be
 * changed with SQL, which is a different tool with a different set of
 * permissions.
 *
 * <p>
 * Nothing here is a rejection. A currency absent from this table is still
 * accepted in a transaction and its rate is fetched on demand; the table only
 * says what has already been pre-fetched. That is why activation is a paid
 * decision — each active currency costs one request a day — and why the two
 * directions of {@code active} are named methods on the entity rather than a
 * setter.
 */
@Service
@RequiredArgsConstructor
public class CurrencyPairService {

	private final CurrencyPairRepository currencyPairRepository;

	private final CurrencyRepository currencyRepository;

	/**
	 * @return every tracked currency, active or not, so an operator can see what
	 *         was retired as well as what is running
	 */
	@Transactional(readOnly = true)
	public List<CurrencyPair> all() {
		return this.currencyPairRepository.findAllByOrderByBaseCurrencyCodeAsc();
	}

	/**
	 * Starts sweeping a currency, adding it to the table if it was not tracked
	 * before.
	 *
	 * @param rawCode
	 *            the code in whatever case the caller had it
	 * @return the tracked pair, now active
	 * @throws NotFoundException
	 *             when the currency is not in the lookup — a rate row references
	 *             {@code currency(code)}, so tracking a currency that does not
	 *             exist would produce a sweep that fails every day
	 */
	@Transactional
	public CurrencyPair activate(String rawCode) {
		String code = normalize(rawCode);
		CurrencyPair pair = this.currencyPairRepository.findByBaseCurrencyCode(code)
				.orElseGet(() -> this.currencyPairRepository.save(new CurrencyPair(currencyOf(code), true)));

		if (!pair.isActive()) {
			pair.activate();
		}
		return pair;
	}

	/**
	 * Retires a currency from the sweep. The row and its stored rates stay: this is
	 * a decision to stop paying for it, not a decision that it never mattered.
	 *
	 * <p>
	 * A currency that is not tracked at all is a 404 rather than a silent insert. A
	 * row created inactive would claim that the currency had been tracked, and
	 * would answer a typo with a 200 instead of saying there is nothing to retire.
	 *
	 * @throws NotFoundException
	 *             when the currency is not tracked
	 */
	@Transactional
	public CurrencyPair deactivate(String rawCode) {
		String code = normalize(rawCode);
		CurrencyPair pair = this.currencyPairRepository.findByBaseCurrencyCode(code)
				.orElseThrow(() -> new NotFoundException(
						"Currency '%s' is not tracked; there is nothing to retire. See /api/v1/currency-pairs"
								.formatted(code)));

		pair.deactivate();
		return pair;
	}

	private Currency currencyOf(String code) {
		return this.currencyRepository.findByCode(code).orElseThrow(() -> NotFoundException.currency(code));
	}

	/**
	 * @return the uppercase code, the only form the schema accepts — the lookup
	 *         column carries the same check, so a lowercase code that got this far
	 *         would be refused by the database with a much less useful message
	 */
	private static String normalize(String rawCode) {
		String code = (rawCode != null) ? rawCode.trim().toUpperCase(Locale.ROOT) : "";
		if (!code.matches("^[A-Z]{3}$")) {
			throw new IllegalArgumentException(
					"Expected a three letter ISO 4217 currency code, got: '" + rawCode + "'");
		}
		return code;
	}

}
