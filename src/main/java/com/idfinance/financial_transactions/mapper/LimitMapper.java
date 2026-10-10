package com.idfinance.financial_transactions.mapper;

import com.idfinance.financial_transactions.config.LedgerPolicy;
import com.idfinance.financial_transactions.dto.LimitResponse;
import com.idfinance.financial_transactions.service.ReportingService;
import java.math.BigDecimal;
import org.mapstruct.Mapper;

/**
 * The limits report, as the client-facing API returns it.
 *
 * <p>
 * Exists because the controller built a nine-argument {@code LimitResponse} by
 * hand: a positional argument per field, no compile-time check the day one is
 * added, and nothing to point at when one is wrong. This is the mapper the
 * project convention says that call site should have had.
 *
 * <p>
 * A {@code default} method rather than declarative mappings, for the same
 * reason {@link TransactionMapper} uses them: every amount here is rounded on
 * the way out, which is a decision about the response rather than a field that
 * can be copied, and an inline expression in an annotation would leave nothing
 * to name when it is wrong.
 *
 * <p>
 * The policy is a parameter rather than something the mapper reaches for: the
 * display scale belongs to the caller that decided to answer with money.
 */
@Mapper(componentModel = "spring")
public interface LimitMapper {

	/**
	 * One row of the limits report.
	 *
	 * <p>
	 * Every amount is rounded here and nowhere else. The stored number keeps the
	 * precision it was given — a limit of 0.001 is a limit, and a transaction in a
	 * low-rate currency converts to a fraction of a unit — and this is where a
	 * client is shown money at a scale it can read.
	 */
	default LimitResponse toResponse(String accountFrom, ReportingService.LimitRow row, LedgerPolicy policy) {
		return new LimitResponse(accountFrom, row.expenseCategory(), money(row.limitSum(), policy),
				row.limitCurrencyShortname(), row.limitDatetime(), row.limitDefault(), row.limitCurrencyShortname(),
				money(row.spentLedger(), policy), money(row.remaining(), policy));
	}

	/**
	 * Rounding applied to every amount on its way out of the API.
	 *
	 * <p>
	 * Already-rounded input comes back unchanged rather than being scaled again:
	 * {@code setScale} with {@code HALF_UP} is idempotent, so a value that arrived
	 * at the display scale is not perturbed by a second pass.
	 */
	static BigDecimal money(BigDecimal amount, LedgerPolicy policy) {
		return (amount == null) ? null : policy.round(amount);
	}

}
