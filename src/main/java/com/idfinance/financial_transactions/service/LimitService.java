package com.idfinance.financial_transactions.service;

import com.idfinance.financial_transactions.config.LedgerPolicy;
import com.idfinance.financial_transactions.dto.LimitRequest;
import com.idfinance.financial_transactions.entity.Currency;
import com.idfinance.financial_transactions.entity.ExpenseCategory;
import com.idfinance.financial_transactions.entity.ExpenseLimit;
import com.idfinance.financial_transactions.exception.NotFoundException;
import com.idfinance.financial_transactions.logging.SafeLog;
import com.idfinance.financial_transactions.metrics.MetricsRecorder;
import com.idfinance.financial_transactions.repository.CurrencyRepository;
import com.idfinance.financial_transactions.repository.ExpenseCategoryRepository;
import com.idfinance.financial_transactions.repository.ExpenseLimitRepository;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Installs limits. Reporting lives in {@link ReportingService}; this service
 * only writes.
 *
 * <p>
 * It deliberately knows nothing about HTTP: it raises domain exceptions and the
 * handler in the {@code exception} package decides which status they mean.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LimitService {

	private final ExpenseLimitRepository expenseLimitRepository;

	private final ExpenseCategoryRepository expenseCategoryRepository;

	private final CurrencyRepository currencyRepository;

	private final LedgerPolicy ledgerPolicy;

	private final MetricsRecorder metricsRecorder;

	private final Clock clock;

	/**
	 * Installs a new limit.
	 *
	 * <p>
	 * A client may install as many limits as they like for the same category: that
	 * is how the specification's case 1 raises the limit from 1000 to 2000 USD on
	 * 10 January. What is forbidden is <em>updating</em> an existing limit, so this
	 * method only ever appends.
	 *
	 * <p>
	 * The installation time is always now, which is what prevents a client from
	 * backdating a limit and retroactively changing flags on older transactions.
	 */
	@Transactional
	public ExpenseLimit install(LimitRequest request) {
		ExpenseCategory category = category(request.expenseCategory());
		Currency ledger = ledgerCurrency();

		// Stored exactly as the client sent it. `amount` is numeric(20, 8) now, so
		// 0.001 is
		// a limit and not something that rounds to zero and trips the positive-amount
		// check.
		// Rounding is a presentation decision — LedgerPolicy.round belongs on the way
		// out,
		// where a client reads the number, not on the way in, where it would decide
		// what
		// the number is allowed to be.
		ExpenseLimit installed = this.expenseLimitRepository.save(new ExpenseLimit(request.accountFrom(),
				category.getId(), ledger, request.sum(), Instant.now(this.clock)));

		this.metricsRecorder.limitInstalled(category.getCode());
		log.info("Installed a {} {} {} limit for account {} at {}", request.sum(), ledger.getCode(), category.getCode(),
				SafeLog.account(request.accountFrom()), installed.getCreatedAt());
		return installed;
	}

	ExpenseCategory category(String code) {
		return this.expenseCategoryRepository.findByCode(code).orElseThrow(() -> NotFoundException.category(code));
	}

	/**
	 * A limit is denominated in the ledger currency, so its row points at that
	 * currency rather than repeating the code.
	 */
	private Currency ledgerCurrency() {
		String code = this.ledgerPolicy.currencyCode();
		return this.currencyRepository.findByCode(code).orElseThrow(() -> NotFoundException.currency(code));
	}

}
