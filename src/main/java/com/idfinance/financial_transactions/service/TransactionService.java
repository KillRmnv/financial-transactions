package com.idfinance.financial_transactions.service;

import com.idfinance.financial_transactions.config.LedgerPolicy;
import com.idfinance.financial_transactions.domain.MonthBoundary;
import com.idfinance.financial_transactions.dto.TransactionRequest;
import com.idfinance.financial_transactions.entity.Currency;
import com.idfinance.financial_transactions.entity.ExpenseCategory;
import com.idfinance.financial_transactions.entity.ExpenseLimit;
import com.idfinance.financial_transactions.entity.ExpenseTransaction;
import com.idfinance.financial_transactions.exception.NotFoundException;
import com.idfinance.financial_transactions.mapper.TransactionMapper;
import com.idfinance.financial_transactions.repository.CurrencyRepository;
import com.idfinance.financial_transactions.repository.ExpenseCategoryRepository;
import com.idfinance.financial_transactions.repository.ExpenseLimitRepository;
import com.idfinance.financial_transactions.repository.ExpenseTransactionRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Accepts transactions and decides whether each one exceeded its limit.
 *
 * <p>
 * The flag follows the rule verified against every row of the truth table in
 * the specification:
 *
 * <pre>
 * limit_exceeded = (running total of the month, this transaction included) &gt; (limit in effect)
 * </pre>
 *
 * <p>
 * Two consequences are easy to get wrong. A balance of exactly zero is
 * <em>not</em> exceeded, because the comparison is strict. And installing a new
 * limit does not reset the month: transactions predating it still count, which
 * is why the remaining balance is 900 rather than 2000 on 10.01.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionService {

	private final ExpenseTransactionRepository expenseTransactionRepository;

	private final ExpenseLimitRepository expenseLimitRepository;

	private final ExpenseCategoryRepository expenseCategoryRepository;

	private final CurrencyRepository currencyRepository;

	private final ExchangeRateService exchangeRateService;

	private final MonthBoundary monthBoundary;

	private final TransactionMapper transactionMapper;

	private final BigDecimal defaultLimitSum;

	private final LedgerPolicy ledgerPolicy;

	private final Clock clock;

	/**
	 * Accepts one transaction.
	 *
	 * <p>
	 * The category and currency codes travel with the result because the web layer
	 * must not reach into the repository to resolve them.
	 */
	@Transactional
	public AcceptedTransaction accept(TransactionRequest request) {
		ExpenseCategory category = category(request.expenseCategory());
		Currency currency = currency(request.currencyShortname());

		// The instant and its month are the server's reading of the timestamp, in the
		// one configured
		// timezone, not the client's assertion about either.
		Instant occurredAt = request.datetime().toInstant();
		ExpenseTransaction transaction = this.transactionMapper.toEntity(request, currency, category.getId(),
				occurredAt, this.monthBoundary.monthOf(occurredAt));
		AcceptedTransaction accepted = new AcceptedTransaction(transaction, category.getCode(), currency.getCode());

		// The rate is resolved before the lock is taken: an external call takes
		// seconds, and holding a row
		// lock across it would block every other transaction of the client.
		ExchangeRateService.Conversion conversion = this.exchangeRateService.convert(transaction.getSum(), currency,
				transaction.getOccurredMonth());
		if (!conversion.isAvailable()) {
			// No rate anywhere. The transaction is still stored, so nothing is lost, but
			// the flag stays
			// uncalculated rather than being invented. Such rows are recalculated once the
			// rate appears.
			transaction.markFlagUncalculated();
			log.warn("Stored a transaction of {} {} without a flag: no exchange rate available", transaction.getSum(),
					currency.getCode());
			this.expenseTransactionRepository.save(transaction);
			return accepted;
		}
		transaction.recordConversion(conversion.sumInLedger(), conversion.rate(), conversion.rateDate());

		applyFlag(transaction, category);
		this.expenseTransactionRepository.save(transaction);
		log.debug("Stored {} {} on {} as {} {}, exceeded={}", transaction.getSum(), currency.getCode(),
				transaction.getOccurredAt(), transaction.getSumInLedger(), this.ledgerPolicy.currencyCode(),
				transaction.getLimitExceeded());
		return accepted;
	}

	/**
	 * Takes the lock, reads the month total and records the outcome.
	 *
	 * <p>
	 * Step one materialises the default limit, because there is nothing to lock
	 * otherwise. Step two locks it with {@code FOR UPDATE}, which is what
	 * serialises two concurrent posts for the same account and category: the second
	 * blocks until the first commits and only then reads the total, so the two
	 * never observe the same remaining balance.
	 */
	private void applyFlag(ExpenseTransaction transaction, ExpenseCategory category) {
		ensureLimitExists(transaction, category);
		ExpenseLimit limit = this.expenseLimitRepository
				.lockEffectiveOn(transaction.getAccountFrom(), category.getId(), transaction.getOccurredAt())
				.orElseThrow(() -> new IllegalStateException(
						"No limit exists for account %s and category %s even after the default was ensured"
								.formatted(transaction.getAccountFrom(), category.getCode())));

		BigDecimal spentBefore = this.expenseTransactionRepository.sumInLedgerForMonth(transaction.getAccountFrom(),
				category.getId(), transaction.getOccurredMonth());
		BigDecimal runningTotal = spentBefore.add(transaction.getSumInLedger());
		boolean exceeded = runningTotal.compareTo(limit.getSum()) > 0;

		transaction.assignFlag(limit.getId(), exceeded);
		log.info("Account {} spent {} of a {} {} limit this month; transaction {} exceeded", limit.getAccountFrom(),
				runningTotal, limit.getSum(), category.getCode(), exceeded);
	}

	/**
	 * Creates the standing default limit if the client never installed one.
	 *
	 * <p>
	 * The row is stamped at the epoch rather than at "now". A transaction can
	 * arrive with a date from before any limit was ever asked for, and the fallback
	 * has to cover it: the specification says the limit is 1000 USD when none was
	 * set, with no start date attached to that fallback.
	 *
	 * <p>
	 * One idempotent statement, so two concurrent first transactions cannot produce
	 * two rows.
	 */
	private void ensureLimitExists(ExpenseTransaction transaction, ExpenseCategory category) {
		this.expenseLimitRepository.createDefaultIfAbsent(transaction.getAccountFrom(), category.getId(),
				this.defaultLimitSum, ledgerCurrencyId());
	}

	/**
	 * The default limit is denominated in the ledger currency, so its row points at
	 * that currency rather than repeating the code.
	 */
	private Long ledgerCurrencyId() {
		String code = this.ledgerPolicy.currencyCode();
		return this.currencyRepository.findByCode(code).orElseThrow(() -> NotFoundException.currency(code)).getId();
	}

	ExpenseCategory category(String code) {
		return this.expenseCategoryRepository.findByCode(code).orElseThrow(() -> NotFoundException.category(code));
	}

	/**
	 * The currency is looked up, never echoed back: an unverified code is exactly
	 * what the lookup table exists to prevent.
	 */
	Currency currency(String code) {
		String normalized = code.trim().toUpperCase(Locale.ROOT);
		return this.currencyRepository.findByCode(normalized).orElseThrow(() -> NotFoundException.currency(code));
	}

	/**
	 * A transaction whose currency is not in the lookup is rejected outright.
	 * Anything less than a reference check would let {@code kzt} through as
	 * something other than {@code KZT}.
	 */
	@Transactional(readOnly = true)
	public List<String> supportedCurrencies() {
		return this.currencyRepository.findAll().stream().map(Currency::getCode).sorted().toList();
	}

}
