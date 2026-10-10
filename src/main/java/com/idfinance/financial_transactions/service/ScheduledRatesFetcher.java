package com.idfinance.financial_transactions.service;

import com.idfinance.financial_transactions.config.LedgerPolicy;
import com.idfinance.financial_transactions.config.SchedulerLockConfig;
import com.idfinance.financial_transactions.domain.ExchangeRate;
import com.idfinance.financial_transactions.entity.Currency;
import com.idfinance.financial_transactions.entity.CurrencyPair;
import com.idfinance.financial_transactions.metrics.MetricsRecorder;
import com.idfinance.financial_transactions.repository.CurrencyPairRepository;
import com.idfinance.financial_transactions.repository.CurrencyRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Refreshes the rates of the currencies marked active in {@code currency_pair}.
 *
 * <p>
 * The set comes from the database rather than from configuration, so an
 * operator can add or retire one without a release. Rates for currencies
 * outside that set are fetched on demand by {@link ExchangeRateService},
 * because each external call costs money and sweeping currencies nobody
 * transacts in would waste that budget.
 *
 * <p>
 * The quote is not read from anywhere and never compared with anything:
 * {@link LedgerPolicy} supplies it and the table does not store a second copy.
 *
 * <p>
 * The external calls run concurrently on virtual threads, which is the point of
 * the specification's optional requirement: they are network-bound and slow, so
 * serialising them made the sweep take as long as the sum of every provider
 * round trip. Virtual threads rather than a fixed pool because the number of
 * currencies is a data decision the operator controls, and a pool would have to
 * guess it.
 *
 * <p>
 * Only the calls are parallel. Reading the tracked set and writing the results
 * are each a single transaction taken through {@link TransactionTemplate},
 * deliberately not with {@code @Transactional}: a self-invoked method is not
 * proxied, so the annotation would silently not apply, and holding one
 * transaction open across the whole sweep would keep a database connection
 * checked out for the duration of every network call. Storing in one batch
 * after the parallel phase also means a failure halfway cannot leave the table
 * half refreshed.
 */
@Component
@ConditionalOnProperty(name = "app.scheduling.enabled", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
// Not final: refreshDaily carries @SchedulerLock, which is an AOP aspect, and
// CGLIB cannot subclass a
// final class. The failure is not a startup error — the class is made non-final
// at build time — it is a
// context that cannot start, which takes down every scenario in the suite
// rather than one. The same
// reasoning as WeeklyCurrencyImport, and ArchitectureTest does not catch it
// because @SchedulerLock is not
// one of the annotations needsAProxy() knows about.
public class ScheduledRatesFetcher {

	private final CurrencyPairRepository currencyPairRepository;

	private final CurrencyRepository currencyRepository;

	private final RatesProvider ratesProvider;

	private final ExchangeRateService exchangeRateService;

	private final LedgerPolicy ledgerPolicy;

	private final Clock clock;

	private final TransactionTemplate transactionTemplate;

	private final MetricsRecorder metricsRecorder;

	private final RateFetchCoordinator rateFetchCoordinator;

	/**
	 * How long one currency may take before the sweep gives up on it. Derived from
	 * the provider's own timeouts rather than guessed: three attempts with a pause
	 * between them, and a margin for the retry.
	 */
	private final Duration sweepTimeout;

	@Scheduled(cron = "0 0 ${app.scheduled-fetch-hour} * * *", zone = "${app.month-timezone}")
	@SchedulerLock(name = SchedulerLockConfig.Names.DAILY_RATES, lockAtLeastFor = "PT1M", lockAtMostFor = "PT10M")
	public void refreshDaily() {
		// The first of the month, not today. TransactionService asks for a rate for
		// occurredMonth,
		// which is the first day of the month the transaction belongs to, so a rate
		// stored under any
		// other date is found by neither findForDay nor findLatestOnOrBefore and the
		// next transaction of
		// that month goes to the provider anyway. Verified on a live database: a rate
		// stored for the
		// 10th is invisible to a transaction whose month starts on the 1st.
		LocalDate rateDate = LocalDate.now(this.clock).withDayOfMonth(1);
		Instant startedAt = Instant.now(this.clock);

		List<Currency> bases = trackedCurrencies();
		if (bases.isEmpty()) {
			log.info("No active currencies to refresh for {}", rateDate);
			return;
		}

		String quote = this.ledgerPolicy.currencyCode();
		log.info("Refreshing {} currencies against {} for {} from {}", bases.size(), quote, rateDate,
				this.ratesProvider.name());

		Map<String, ExchangeRate> fetched = fetchConcurrently(bases, quote, rateDate);
		storeAll(fetched);
		this.metricsRecorder.sweepCompleted(Duration.between(startedAt, Instant.now(this.clock)), fetched.size());
	}

	private List<Currency> trackedCurrencies() {
		return this.transactionTemplate.execute(status -> this.currencyPairRepository
				.findByActiveTrueOrderByBaseCurrencyCodeAsc().stream().map(CurrencyPair::getBaseCurrency).toList());
	}

	/**
	 * Fetches every currency at once and collects what came back.
	 *
	 * <p>
	 * One failing currency must not cost the others their rates, so each failure is
	 * logged inside its own task and dropped from the result rather than
	 * propagated: a provider that is down for KZT is no reason to leave RUB's rate
	 * unrefreshed for another day.
	 */
	private Map<String, ExchangeRate> fetchConcurrently(List<Currency> bases, String quote, LocalDate date) {
		Map<String, ExchangeRate> fetched = new LinkedHashMap<>();
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var tasks = bases.stream()
					.map(base -> CompletableFuture.supplyAsync(() -> fetch(base, quote, date), executor)).toList();
			for (var task : tasks) {
				// get() after submitting everything keeps the whole batch in flight; joining
				// inside the
				// loop above would serialise exactly what this method exists to parallelise.
				//
				// Bounded, unlike a plain join(). The provider has a read timeout, so a single
				// call
				// cannot hang forever — but a virtual thread waiting on a task whose supplier
				// is
				// blocked elsewhere can, and a sweep that never returns holds a scheduler slot
				// and a
				// ShedLock lease for good. A currency that misses the deadline is dropped here
				// and
				// recalculated by the next sweep, which is the same bargain fetch() already
				// makes on
				// a provider error.
				var result = await(task, this.sweepTimeout);
				if (result != null) {
					fetched.put(result.currencyCode(), result.rate());
				}
			}
		}
		return fetched;
	}

	private Fetched await(CompletableFuture<Fetched> task, Duration timeout) {
		try {
			return task.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
		} catch (TimeoutException ex) {
			log.warn("A currency did not answer within {} and was dropped from this sweep", timeout);
			task.cancel(true);
			return null;
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return null;
		} catch (java.util.concurrent.ExecutionException ex) {
			log.warn("A currency failed during this sweep: {}", ex.getCause() != null ? ex.getCause() : ex);
			return null;
		}
	}

	private Fetched fetch(Currency base, String quote, LocalDate date) {
		String code = base.getCode();
		try {
			// Through the coordinator for the same reason the transaction path is: a
			// transaction
			// arriving while the sweep is asking pays for nothing if it can join this call
			// instead.
			Optional<ExchangeRate> answer = this.rateFetchCoordinator.rateFor(code, quote, date,
					() -> Optional.ofNullable(this.ratesProvider.rateFor(code, quote, date)));
			ExchangeRate rate = answer.orElse(null);
			if (rate == null) {
				log.info("No {} published for {} on {}", quote, code, date);
				return null;
			}
			return new Fetched(code, rate);
		} catch (RuntimeException ex) {
			log.warn("Could not refresh {}/{} on {}: {}", code, quote, date, ex.getMessage());
			return null;
		}
	}

	/**
	 * The currencies are resolved again inside the writing transaction rather than
	 * carried over from the reading one: those entities are detached by now, and a
	 * rate row must not end up pointing at a detached instance. It is one primary
	 * key lookup per currency, once a day.
	 */
	private void storeAll(Map<String, ExchangeRate> fetched) {
		this.transactionTemplate.executeWithoutResult(status -> fetched.forEach((code, rate) -> {
			Currency currency = this.currencyRepository.findByCode(code).orElseThrow(
					() -> new IllegalStateException("Tracked currency " + code + " vanished from the lookup"));
			this.exchangeRateService.store(currency, rate);
		}));
	}

	private record Fetched(String currencyCode, ExchangeRate rate) {
	}

}
