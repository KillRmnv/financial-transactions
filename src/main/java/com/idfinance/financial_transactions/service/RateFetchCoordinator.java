package com.idfinance.financial_transactions.service;

import com.idfinance.financial_transactions.domain.ExchangeRate;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Makes one external call per currency and day, however many callers ask.
 *
 * <p>
 * Two places reach the provider: the daily sweep, and
 * {@link ExchangeRateService} when a transaction arrives for a currency that
 * has no stored rate. They are separate code paths with no shared state, so
 * before this class a transaction arriving one second after the sweep asked for
 * the same currency paid for a second identical call — the exact waste the
 * specification warns about when it says every external request costs money.
 *
 * <p>
 * The obvious fix is a lock per currency. That is the wrong shape: the callers
 * wait rather than join, so the second thread occupies a Tomcat thread and a
 * pooled connection for the whole duration of a network call it did not make.
 * This coalesces instead. The first caller runs the fetch and publishes a
 * future; every other caller for that pair finds the same future and waits on
 * it, so N concurrent requests produce one call and N results.
 *
 * <p>
 * The key is the pair and the day, because those are what the provider is asked
 * for. Keying on the currency alone would merge a request for today's rate with
 * one for last month's, and the second caller would be handed a rate for the
 * wrong day — a wrong number rather than a duplicate request, which is worse.
 *
 * <p>
 * Futures are removed in a {@code finally} on every path, including a failure.
 * A future left in the map would be a permanent answer to a question that
 * changes tomorrow.
 */
@Component
@Slf4j
public final class RateFetchCoordinator {

	private final Map<String, CompletableFuture<Optional<ExchangeRate>>> inFlight = new ConcurrentHashMap<>();

	/**
	 * Runs {@code fetch} unless an identical fetch is already running, in which
	 * case waits for that one.
	 *
	 * @return what the single call returned, or an empty conversion when there is
	 *         no rate
	 */
	public Optional<ExchangeRate> rateFor(String baseCurrency, String quoteCurrency, LocalDate date,
			Supplier<Optional<ExchangeRate>> fetch) {
		String key = key(baseCurrency, quoteCurrency, date);
		CompletableFuture<Optional<ExchangeRate>> existing = this.inFlight.putIfAbsent(key, new CompletableFuture<>());
		if (existing != null) {
			log.debug("Joining the in-flight fetch of {}/{} for {}", baseCurrency, quoteCurrency, date);
			return join(existing, key);
		}

		CompletableFuture<Optional<ExchangeRate>> mine = this.inFlight.get(key);
		try {
			Optional<ExchangeRate> result = fetch.get();
			mine.complete(result);
			return result;
		} catch (RuntimeException ex) {
			// A failure has to complete the future as well, or every joined caller waits
			// for
			// a timeout instead of learning immediately that the provider is down.
			mine.completeExceptionally(ex);
			throw ex;
		} finally {
			this.inFlight.remove(key, mine);
		}
	}

	/**
	 * A caller that joined must not see a provider exception as its own:
	 * {@link ExchangeRateService} catches that around its own call and stores the
	 * transaction unflagged, and here the joined caller would receive an exception
	 * thrown by a thread it never spoke to.
	 */
	private Optional<ExchangeRate> join(CompletableFuture<Optional<ExchangeRate>> pending, String key) {
		try {
			return pending.join();
		} catch (java.util.concurrent.CompletionException ex) {
			log.debug("The joined fetch of {} failed with the fetch it joined: {}", key, ex.getCause() != null);
			return Optional.empty();
		}
	}

	private String key(String baseCurrency, String quoteCurrency, LocalDate date) {
		return baseCurrency + "/" + quoteCurrency + "@" + date;
	}

	/**
	 * How many fetches are running, for diagnostics and for a scenario that asserts
	 * the coalescing actually happened rather than inferring it from a call count.
	 */
	public int inFlightCount() {
		return this.inFlight.size();
	}

}
