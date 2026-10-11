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
 * for, and the pair is keyed <em>without</em> its orientation: {@code KZT/USD}
 * and {@code USD/KZT} are one question, not two. Keying on the currency alone
 * would merge a request for today's rate with one for last month's, and the
 * second caller would be handed a rate for the wrong day — a wrong number
 * rather than a duplicate request, which is worse. The orientation that a
 * shared key loses is restored on the way out, so sharing it changes nothing a
 * caller can observe.
 *
 * <p>
 * One fetch per key covers the whole provider chain, not one per provider: the
 * supplier it is given is the chain itself, and a thread only walks to the next
 * provider when the previous one had no answer. Two callers for one pair
 * therefore never produce a request to each of the providers — which is worth
 * stating because per-provider coalescing would be the obvious misreading of
 * this class, and it would be a lock per provider for no gain.
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
	 * <p>
	 * The answer is returned in the orientation the caller asked for, whichever
	 * orientation the thread that did the work happened to ask in. See
	 * {@link #orientedTo}.
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
			return orientedTo(join(existing, key), baseCurrency, quoteCurrency);
		}

		CompletableFuture<Optional<ExchangeRate>> mine = this.inFlight.get(key);
		try {
			Optional<ExchangeRate> result = fetch.get();
			mine.complete(result);
			return orientedTo(result, baseCurrency, quoteCurrency);
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
	 * The answer as the caller asked for it.
	 *
	 * <p>
	 * Necessary because the key is symmetric: one call for {@code KZT/USD} and one
	 * for {@code USD/KZT} are the same question, so the second caller waits for the
	 * first and gets an answer pointing the other way. Handing that over unchanged
	 * is the worse of the two mistakes — {@code ExchangeRateService} would store a
	 * rate quoted in the opposite orientation under the right pair, and the ledger
	 * would carry a number off by the square of the rate.
	 * {@link ExchangeRate#inverted()} turns it round, previous close included, so a
	 * holiday rate is inverted on both sides rather than one.
	 *
	 * <p>
	 * Only a pair that is exactly the reverse of the request is inverted. A rate
	 * that names neither side is passed through rather than guessed at: a provider
	 * answering about a different pair is a defect to be seen in the data, not one
	 * to paper over with a division.
	 */
	private static Optional<ExchangeRate> orientedTo(Optional<ExchangeRate> rate, String baseCurrency,
			String quoteCurrency) {
		if (rate.isEmpty()) {
			return rate;
		}
		ExchangeRate answer = rate.get();
		boolean reversed = answer.baseCurrency().equals(quoteCurrency) && answer.quoteCurrency().equals(baseCurrency);
		return reversed ? rate.map(ExchangeRate::inverted) : rate;
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

	/**
	 * The in-flight key, which names the pair without an orientation.
	 *
	 * <p>
	 * {@code KZT/USD} and {@code USD/KZT} are the same question asked backwards,
	 * and the provider answers both from one number. Keying them apart sent two
	 * calls for one rate whenever both orientations were in flight at once — the
	 * case this class exists to prevent, and it made a thousandfold difference to
	 * the answer if the two callers' numbers were then swapped.
	 *
	 * <p>
	 * Sorting the two sides is what merges them; the orientation that is lost is
	 * put back by {@link #orientedTo} on the way out, so nothing downstream has to
	 * know that a key was shared.
	 */
	private String key(String baseCurrency, String quoteCurrency, LocalDate date) {
		String pair = (baseCurrency.compareTo(quoteCurrency) <= 0)
				? baseCurrency + "/" + quoteCurrency
				: quoteCurrency + "/" + baseCurrency;
		return pair + "@" + date;
	}

	/**
	 * How many fetches are running, for diagnostics and for a scenario that asserts
	 * the coalescing actually happened rather than inferring it from a call count.
	 */
	public int inFlightCount() {
		return this.inFlight.size();
	}

}
