package com.idfinance.financial_transactions.bdd.rule;

import com.idfinance.financial_transactions.domain.ExchangeRate;
import com.idfinance.financial_transactions.service.RateFetchCoordinator;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.assertj.core.api.Assertions;

/**
 * The rate-fetch coordinator, on its own.
 *
 * <p>
 * It has no Spring in it and nothing to reach for but its own map, so it
 * belongs to this suite rather than to one that needs a database: the property
 * under test is a statement about two threads and a hash map, and it is checked
 * here in milliseconds and buried behind an HTTP request anywhere else.
 */
class RateFetchCoordinatorSteps {

	private final RateFetchCoordinator coordinator = new RateFetchCoordinator();

	private final AtomicInteger fetches = new AtomicInteger();

	private final List<Optional<ExchangeRate>> answers = new ArrayList<>();

	/** The rate the provider hands back, and the pair it quotes it in. */
	private BigDecimal answer;

	private String answeredAs;

	@Given("the provider answers {string} against {string} at {string}")
	void theProviderAnswers(String baseCurrency, String quoteCurrency, String rate) {
		this.answeredAs = baseCurrency + "/" + quoteCurrency;
		this.answer = new BigDecimal(rate);
	}

	@When("two callers ask at once in the same orientation, {string} against {string}")
	void twoCallersInTheSameOrientation(String baseCurrency, String quoteCurrency) throws Exception {
		askTogether(baseCurrency, quoteCurrency, baseCurrency, quoteCurrency);
	}

	@When("two callers ask at once, {string} against {string} and {string} against {string}")
	void twoCallersBothWaysRound(String firstBase, String firstQuote, String secondBase, String secondQuote)
			throws Exception {
		askTogether(firstBase, firstQuote, secondBase, secondQuote);
	}

	/**
	 * Two callers, the first held inside the fetch until the second has had its
	 * turn at the map.
	 *
	 * <p>
	 * The wait is what makes the two overlap. It is bounded rather than signalled,
	 * because there is no hook inside {@code putIfAbsent} to signal from and a
	 * latch the second caller trips on its way in would leave the same window open.
	 * The first caller publishes its in-flight entry <em>before</em> it runs the
	 * supplier, so by the time that latch has fired the entry is already there and
	 * the second caller only has to be scheduled — which is microseconds, against a
	 * wait that is not.
	 */
	private void askTogether(String firstBase, String firstQuote, String secondBase, String secondQuote)
			throws Exception {
		LocalDate day = LocalDate.parse("2022-01-01");
		CountDownLatch firstInside = new CountDownLatch(1);
		CountDownLatch secondWouldHaveFetched = new CountDownLatch(1);

		Supplier<Optional<ExchangeRate>> firstFetch = () -> {
			this.fetches.incrementAndGet();
			firstInside.countDown();
			try {
				secondWouldHaveFetched.await(250, TimeUnit.MILLISECONDS);
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			return rateFor(firstBase, firstQuote, day);
		};
		Supplier<Optional<ExchangeRate>> secondFetch = () -> {
			this.fetches.incrementAndGet();
			secondWouldHaveFetched.countDown();
			return rateFor(secondBase, secondQuote, day);
		};

		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			Future<Optional<ExchangeRate>> first = pool
					.submit(() -> this.coordinator.rateFor(firstBase, firstQuote, day, firstFetch));
			if (!firstInside.await(10, TimeUnit.SECONDS)) {
				throw new AssertionError("the first caller never reached the provider");
			}
			Future<Optional<ExchangeRate>> second = pool
					.submit(() -> this.coordinator.rateFor(secondBase, secondQuote, day, secondFetch));
			this.answers.add(first.get(30, TimeUnit.SECONDS));
			this.answers.add(second.get(30, TimeUnit.SECONDS));
		}
	}

	private Optional<ExchangeRate> rateFor(String baseCurrency, String quoteCurrency, LocalDate day) {
		String[] sides = this.answeredAs.split("/");
		return Optional.of(new ExchangeRate(sides[0], sides[1], day, this.answer, null, "stub"));
	}

	@Then("the provider was asked {int} times")
	void theProviderWasAskedTimes(int expected) {
		Assertions.assertThat(this.fetches.get()).as("calls made to the provider").isEqualTo(expected);
	}

	@Then("both callers get a rate")
	void bothCallersGetARate() {
		Assertions.assertThat(this.answers).as("answers handed back").hasSize(2)
				.allSatisfy(rate -> Assertions.assertThat(rate).as("a rate, not an absence").isPresent());
	}

	@Then("the caller that asked {string} against {string} gets {string}")
	void theCallerThatAskedGets(String baseCurrency, String quoteCurrency, String expected) {
		ExchangeRate rate = this.answerFor(baseCurrency, quoteCurrency);
		Assertions.assertThat(rate.baseCurrency()).as("the base it was answered in").isEqualTo(baseCurrency);
		Assertions.assertThat(rate.quoteCurrency()).as("the quote it was answered in").isEqualTo(quoteCurrency);
		Assertions.assertThat(rate.effectiveRate()).as("the rate it was answered with")
				.isEqualByComparingTo(new BigDecimal(expected));
	}

	/**
	 * Which answer belongs to which caller.
	 *
	 * <p>
	 * Matched on the orientation it came back in rather than on the order the
	 * callers were submitted: the point of the change is that orientation no longer
	 * follows from the key, so a step that assumed it did would be asserting the
	 * bug.
	 */
	private ExchangeRate answerFor(String baseCurrency, String quoteCurrency) {
		return this.answers.stream().flatMap(Optional::stream)
				.filter(rate -> rate.baseCurrency().equals(baseCurrency) && rate.quoteCurrency().equals(quoteCurrency))
				.findFirst()
				.orElseThrow(() -> new AssertionError("no answer came back in the orientation " + baseCurrency + "/"
						+ quoteCurrency + ", what came back: "
						+ this.answers.stream().flatMap(Optional::stream).map(ExchangeRate::pairKey).toList()));
	}

}
