package com.idfinance.financial_transactions.service;

import com.idfinance.financial_transactions.config.LedgerPolicy;
import com.idfinance.financial_transactions.config.RateTimeouts;
import com.idfinance.financial_transactions.domain.MonthBoundary;
import com.idfinance.financial_transactions.dto.TransactionRequest;
import com.idfinance.financial_transactions.entity.Currency;
import com.idfinance.financial_transactions.entity.ExpenseCategory;
import com.idfinance.financial_transactions.entity.ExpenseLimit;
import com.idfinance.financial_transactions.entity.ExpenseTransaction;
import com.idfinance.financial_transactions.exception.BatchResolutionFailedException;
import com.idfinance.financial_transactions.exception.LockUnavailableException;
import com.idfinance.financial_transactions.exception.NotFoundException;
import com.idfinance.financial_transactions.mapper.TransactionMapper;
import com.idfinance.financial_transactions.metrics.MetricsRecorder;
import com.idfinance.financial_transactions.repository.CurrencyRepository;
import com.idfinance.financial_transactions.repository.ExpenseCategoryRepository;
import com.idfinance.financial_transactions.repository.ExpenseLimitRepository;
import com.idfinance.financial_transactions.repository.ExpenseTransactionRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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

	/**
	 * Outcomes are a closed set, because they are a metric tag and a tag with a
	 * typo is a silent hole.
	 */
	private static final String FLAGGED = "flagged";

	private static final String WITHIN_LIMIT = "within_limit";

	private static final String UNCALCULATED = "uncalculated";

	private final ExpenseTransactionRepository expenseTransactionRepository;

	private final ExpenseLimitRepository expenseLimitRepository;

	private final ExpenseCategoryRepository expenseCategoryRepository;

	private final CurrencyRepository currencyRepository;

	private final ExchangeRateService exchangeRateService;

	private final MonthBoundary monthBoundary;

	private final TransactionMapper transactionMapper;

	private final BigDecimal defaultLimitSum;

	private final LedgerPolicy ledgerPolicy;

	private final MetricsRecorder metricsRecorder;

	private final Clock clock;

	private final TransactionTemplate transactionTemplate;

	/**
	 * How long the rate phase of a batch may take before the batch is abandoned.
	 *
	 * <p>
	 * Generous on purpose: a batch of twenty transactions in twenty currencies
	 * costs twenty external calls, and the point of the phase is that they overlap.
	 * Bounding it is not about speed but about a provider that never answers, which
	 * would otherwise hold the request thread indefinitely.
	 */
	private final RateTimeouts rateTimeouts;

	/**
	 * Accepts one transaction.
	 *
	 * <p>
	 * The category and currency codes travel with the result because the web layer
	 * must not reach into the repository to resolve them.
	 */
	@Transactional
	public AcceptedTransaction accept(TransactionRequest request) {
		return store(prepare(request, currency(request.currencyShortname()), category(request.expenseCategory())));
	}

	/**
	 * Accepts several transactions at once, resolving their rates in parallel.
	 *
	 * <p>
	 * The two phases are split on purpose, and which of them runs concurrently is
	 * the whole design.
	 *
	 * <p>
	 * <b>Preparing</b> resolves the category, the currency and the exchange rate.
	 * The rate is the slow part: a currency the service has never seen costs an
	 * external call measured in seconds, and a client posting a day's transactions
	 * across several currencies pays for those calls one after another otherwise.
	 * They are independent of each other, so they run at once.
	 *
	 * <p>
	 * <b>Storing</b> runs sequentially, each transaction in its own transaction. It
	 * cannot usefully be parallel: two of them for one account and category contend
	 * for the same limit row, and the {@code PESSIMISTIC_WRITE} lock that keeps the
	 * running total correct would serialise them anyway — thread scheduling to
	 * arrive at the queue the lock already imposes.
	 *
	 * <p>
	 * A transaction whose rate is unavailable is stored without a flag, exactly as
	 * on the single-request path, and it does not stop the others: one currency the
	 * provider cannot answer is no reason to refuse the rest of a batch. A failure
	 * that is not about rates — an unknown currency, a constraint — aborts the
	 * batch, because by then earlier transactions are already stored and reporting
	 * a uniform failure for a partially written batch would be a lie.
	 *
	 * @param requests
	 *            the batch, validated as a whole by the caller
	 * @return one result per request, in the order they were sent
	 */
	public List<AcceptedTransaction> acceptBatch(List<TransactionRequest> requests) {
		if (requests.isEmpty()) {
			return List.of();
		}
		return resolveRates(requests).stream().map(this::storeInItsOwnTransaction).toList();
	}

	/**
	 * The rate phase: one virtual thread per transaction.
	 *
	 * <p>
	 * A virtual thread per task rather than a fixed pool because the batch size is
	 * the caller's decision and a pool would have to guess it — the same reason the
	 * daily sweep works this way.
	 *
	 * <p>
	 * Each task opens its own transaction through {@link TransactionTemplate}.
	 * These are new threads, so there is no transaction to inherit and no ambient
	 * one to propagate; without the template each preparation would read outside a
	 * transaction.
	 */
	private List<Prepared> resolveRates(List<TransactionRequest> requests) {
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var tasks = requests.stream()
					.map(request -> CompletableFuture.supplyAsync(
							() -> this.transactionTemplate.execute(status -> prepare(request,
									currency(request.currencyShortname()), category(request.expenseCategory()))),
							executor))
					.toList();

			List<Prepared> prepared = new ArrayList<>(requests.size());
			for (var task : tasks) {
				try {
					// Everything is submitted before anything is joined, so the whole batch is in
					// flight; joining inside the submission loop would serialise precisely what
					// this
					// method exists to parallelise.
					prepared.add(task.get(this.rateTimeouts.batch().toMillis(), TimeUnit.MILLISECONDS));
				} catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException("The batch was interrupted while resolving rates", ex);
				} catch (TimeoutException ex) {
					task.cancel(true);
					throw new BatchResolutionFailedException(requests.size(), ex);
				} catch (ExecutionException ex) {
					throw unwrap(ex);
				}
			}
			return prepared;
		}
	}

	/**
	 * A failure inside a task arrives wrapped, and an unknown currency or a
	 * constraint violation would otherwise leave the API answering 500 for what is
	 * a 404.
	 */
	private RuntimeException unwrap(ExecutionException ex) {
		Throwable cause = ex.getCause();
		if (cause instanceof RuntimeException runtime) {
			return runtime;
		}
		return new BatchResolutionFailedException(1, ex);
	}

	/**
	 * The store phase, in a transaction of its own because the caller of a batch
	 * holds none.
	 */
	private AcceptedTransaction storeInItsOwnTransaction(Prepared prepared) {
		return this.transactionTemplate.execute(status -> store(prepared));
	}

	/**
	 * Everything the store phase needs, already resolved.
	 *
	 * <p>
	 * The row is detached by the time it is stored, which is why the codes travel
	 * with it rather than being resolved again on the far side of the phase
	 * boundary.
	 *
	 * @param transaction
	 *            the mapped row, with its conversion recorded when a rate was
	 *            available
	 * @param category
	 *            the flag is decided per category, so the store phase still needs
	 *            it
	 */
	private record Prepared(ExpenseTransaction transaction, ExpenseCategory category) {
	}

	/**
	 * Maps the request and resolves its rate. Shared by both paths so the
	 * single-request and the batch behaviour cannot drift apart.
	 *
	 * <p>
	 * The rate is resolved here, before any lock: an external call takes seconds,
	 * and holding a row lock across it would block every other transaction of the
	 * client.
	 */
	private Prepared prepare(TransactionRequest request, Currency currency, ExpenseCategory category) {
		// The instant and its month are the server's reading of the timestamp, in the
		// one configured
		// timezone, not the client's assertion about either.
		Instant occurredAt = request.datetime().toInstant();
		ExpenseTransaction transaction = this.transactionMapper.toEntity(request, currency, category.getId(),
				occurredAt, this.monthBoundary.monthOf(occurredAt));

		ExchangeRateService.Conversion conversion = this.exchangeRateService.convert(transaction.getAmount(), currency,
				transaction.getOccurredMonth());
		if (!conversion.isAvailable()) {
			// No rate anywhere. The transaction is still stored, so nothing is lost, but
			// the flag stays
			// uncalculated rather than being invented. Such rows are recalculated once the
			// rate appears.
			// No flag is assigned and flag_calculated is derived from limit_exceeded, so
			// the row
			// stores itself as uncalculated: there is no method to call here and none to
			// forget.
			log.warn("A transaction of {} {} has no available rate and will be stored without a flag",
					transaction.getAmount(), currency.getCode());
			this.metricsRecorder.transactionAccepted(UNCALCULATED);
			return new Prepared(transaction, category);
		}
		transaction.recordConversion(conversion.sumInLedger(), conversion.rate(), conversion.rateDate());
		return new Prepared(transaction, category);
	}

	/**
	 * Flags and writes a prepared transaction. The caller holds the transaction.
	 */
	private AcceptedTransaction store(Prepared prepared) {
		ExpenseTransaction transaction = prepared.transaction();
		AcceptedTransaction accepted = new AcceptedTransaction(transaction, prepared.category().getCode(),
				transaction.getCurrency().getCode());

		// A null ledger amount means the rate was unavailable in prepare(), which is
		// the only way to
		// reach here without a conversion; the row then stores itself as unflagged.
		if (transaction.getSumInLedger() != null) {
			applyFlag(transaction, prepared.category());
		}
		this.expenseTransactionRepository.save(transaction);
		log.debug("Stored {} {} on {} as {} {}, exceeded={}", transaction.getAmount(),
				transaction.getCurrency().getCode(), transaction.getOccurredAt(), transaction.getSumInLedger(),
				this.ledgerPolicy.currencyCode(), transaction.getLimitExceeded());
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
		ExpenseLimit limit = lockEffectiveLimit(transaction, category);

		BigDecimal spentBefore = this.expenseTransactionRepository.sumInLedgerForMonth(transaction.getAccountFrom(),
				category.getId(), transaction.getOccurredMonth());
		BigDecimal runningTotal = spentBefore.add(transaction.getSumInLedger());
		boolean exceeded = runningTotal.compareTo(limit.getAmount()) > 0;

		transaction.assignFlag(limit.getId(), exceeded);
		this.metricsRecorder.transactionAccepted(exceeded ? FLAGGED : WITHIN_LIMIT);
		log.info("Account {} spent {} of a {} {} limit this month; transaction {} exceeded", limit.getAccountFrom(),
				runningTotal, limit.getAmount(), category.getCode(), exceeded);
	}

	/**
	 * Takes the lock, translating a timeout into a domain exception.
	 *
	 * <p>
	 * The translation is here rather than in the handler because the handler
	 * decides the status and must not know what a lock is. PostgreSQL's
	 * {@code lock_timeout} — set through Hikari's {@code connection-init-sql} in
	 * the {@code prod} profile — arrives here as
	 * {@code CannotAcquireLockException}, which is a retryable "busy", not a broken
	 * service.
	 *
	 * <p>
	 * Without the {@code lock_timeout} the database would wait forever and this
	 * catch would never fire, so the two are one decision: the bound is what makes
	 * the failure visible instead of a pile-up.
	 */
	private ExpenseLimit lockEffectiveLimit(ExpenseTransaction transaction, ExpenseCategory category) {
		try {
			return this.expenseLimitRepository
					.lockEffectiveOn(transaction.getAccountFrom(), category.getId(), transaction.getOccurredAt())
					.orElseThrow(() -> new IllegalStateException(
							"No limit exists for account %s and category %s even after the default was ensured"
									.formatted(transaction.getAccountFrom(), category.getCode())));
		} catch (CannotAcquireLockException ex) {
			log.warn("Timed out waiting for the limit row of account {} and category {}", transaction.getAccountFrom(),
					category.getCode());
			throw new LockUnavailableException(transaction.getAccountFrom(), category.getCode());
		}
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
				this.defaultLimitSum, ledgerCurrencyId(), transaction.getOccurredAt());
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
