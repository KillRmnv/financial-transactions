package com.idfinance.financial_transactions.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The meters themselves, without Spring and without a database.
 *
 * <p>
 * One scenario elsewhere reads a counter back out of the registry, and it was
 * written after a cache that never answered still let every test above it pass
 * — which is the failure a cache is most likely to have. The rest of this class
 * covers what that scenario cannot: the names, the tag sets, and the two places
 * where the same registry name used to carry two different kinds of meter.
 *
 * <p>
 * A {@link SimpleMeterRegistry} rather than the application's is deliberate. It
 * is the same Micrometer code path, without a context, without Testcontainers
 * and in milliseconds — and it fails on a broken meter name rather than on a
 * missing database.
 */
class MetricsRecorderTest {

	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

	private final MetricsRecorder recorder = new MetricsRecorder(this.registry);

	/**
	 * The total count of a meter selected by one tag.
	 *
	 * <p>
	 * Summed over every matching meter rather than read off the first: two calls
	 * with the same source and different providers are two meters, and
	 * {@code find(...).counter()} hands back one of them, which is a test that
	 * would report a failover as no change at all.
	 */
	private double counter(String name, String tagKey, String tagValue) {
		return this.registry.find(name).tag(tagKey, tagValue).counters().stream().mapToDouble((meter) -> meter.count())
				.sum();
	}

	@Test
	@DisplayName("a transaction is counted under the outcome it was stored with")
	void countsTransactionsPerOutcome() {
		this.recorder.transactionAccepted("flagged");
		this.recorder.transactionAccepted("flagged");
		this.recorder.transactionAccepted("within_limit");
		this.recorder.transactionAccepted("uncalculated");

		assertThat(counter("financial.transactions.accepted", "outcome", "flagged")).isEqualTo(2.0d);
		assertThat(counter("financial.transactions.accepted", "outcome", "within_limit")).isEqualTo(1.0d);
		assertThat(counter("financial.transactions.accepted", "outcome", "uncalculated")).isEqualTo(1.0d);
	}

	@Test
	@DisplayName("uncalculated is counted as its own outcome, not folded into the others")
	void uncalculatedIsNotAFlavourOfFalse() {
		// Absence of an answer rather than a flavour of false: an operator needs to see
		// "rates stopped arriving" as its own number.
		this.recorder.transactionAccepted("uncalculated");

		assertThat(counter("financial.transactions.accepted", "outcome", "uncalculated")).isEqualTo(1.0d);
		assertThat(counter("financial.transactions.accepted", "outcome", "within_limit")).isZero();
		assertThat(counter("financial.transactions.accepted", "outcome", "flagged")).isZero();
	}

	@Test
	@DisplayName("a resolved rate is counted by where it came from and which provider answered")
	void countsRatesBySourceAndProvider() {
		this.recorder.rateResolved("cache", "stub");
		this.recorder.rateResolved("database", "stub");
		this.recorder.rateResolved("external", "twelvedata");
		this.recorder.rateResolved("external", "frankfurter");

		assertThat(counter("financial.rates.resolved", "source", "cache")).isEqualTo(1.0d);
		assertThat(counter("financial.rates.resolved", "source", "database")).isEqualTo(1.0d);
		assertThat(counter("financial.rates.resolved", "source", "external")).isEqualTo(2.0d);
	}

	@Test
	@DisplayName("the provider tag is what makes a failover visible in the numbers")
	void providerTagSeparatesAFailover() {
		// The total is unchanged by a failover; the split is what moved.
		this.recorder.rateResolved("external", "twelvedata");
		this.recorder.rateResolved("external", "frankfurter");

		assertThat(counter("financial.rates.resolved", "source", "external")).isEqualTo(2.0d);
		assertThat(counter("financial.rates.resolved", "provider", "twelvedata")).isEqualTo(1.0d);
		assertThat(counter("financial.rates.resolved", "provider", "frankfurter")).isEqualTo(1.0d);
	}

	@Test
	@DisplayName("a lookup is timed, and the answer is returned to the caller untouched")
	void timesTheLookupChain() throws Exception {
		String answer = this.recorder.timeLookup("cache", () -> "rate");

		assertThat(answer).isEqualTo("rate");
		assertThat(this.registry.find("financial.rates.lookup").tag("source", "cache").timer()).isNotNull();
		assertThat(this.registry.find("financial.rates.lookup").tag("source", "cache").timer().count()).isEqualTo(1L);
	}

	@Test
	@DisplayName("a lookup that fails is still timed, and the failure still reaches the caller")
	void timesAFailingLookupAndPropagatesIt() {
		// Checked because the opposite was assumed and would have been the worse
		// behaviour: a
		// provider that fails on every call is precisely where the timing matters, and
		// a timer
		// that only recorded successes would be silent there.
		assertThatThrownBy(() -> this.recorder.timeLookup("external", () -> {
			throw new IllegalStateException("provider is down");
		})).isInstanceOf(IllegalStateException.class).hasMessage("provider is down");

		assertThat(this.registry.find("financial.rates.lookup").tag("source", "external").timer().count())
				.isEqualTo(1L);
	}

	@Test
	@DisplayName("a sweep records its duration and its currency count under different names")
	void recordsSweepDurationAndCountSeparately() {
		this.recorder.sweepCompleted(Duration.ofMillis(120), 7);

		assertThat(this.registry.find("financial.rates.sweep.duration").timer().count()).isEqualTo(1L);
		assertThat(this.registry.find("financial.rates.sweep.duration").timer()
				.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(120.0d);
		assertThat(this.registry.find("financial.rates.sweep.currencies").counter().count()).isEqualTo(7.0d);
	}

	@Test
	@DisplayName("no two meters share a name, whatever kind they are")
	void noTwoMetersShareAName() throws Exception {
		// The defect this replaced: a Timer and a Counter both registered as
		// "financial.rates.sweep", which Micrometer accepts and Prometheus then exposes
		// under two
		// unrelated names — _total for one and _seconds for the other. A panel querying
		// the shared
		// name gets neither, and an empty panel is indistinguishable from a sweep that
		// never ran.
		this.recorder.transactionAccepted("flagged");
		this.recorder.rateResolved("cache", "stub");
		this.recorder.timeLookup("cache", () -> "rate");
		this.recorder.sweepCompleted(Duration.ofMillis(10), 2);
		this.recorder.limitInstalled("product");
		this.recorder.rateLimitRefused("ingestion");

		var names = this.registry.getMeters().stream().map(meter -> meter.getId().getName()).toList();
		assertThat(names).doesNotHaveDuplicates();
		assertThat(names).containsExactlyInAnyOrder("financial.transactions.accepted", "financial.rates.resolved",
				"financial.rates.lookup", "financial.rates.sweep.duration", "financial.rates.sweep.currencies",
				"financial.limits.installed", "financial.rate_limit.refused");
	}

	@Test
	@DisplayName("an installed limit is counted under its category")
	void countsInstalledLimits() {
		this.recorder.limitInstalled("product");
		this.recorder.limitInstalled("service");
		this.recorder.limitInstalled("product");

		assertThat(counter("financial.limits.installed", "category", "product")).isEqualTo(2.0d);
		assertThat(counter("financial.limits.installed", "category", "service")).isEqualTo(1.0d);
	}

	@Test
	@DisplayName("a throttled caller is counted under the quota that refused it")
	void countsRefusalsPerQuota() {
		this.recorder.rateLimitRefused("ingestion");
		this.recorder.rateLimitRefused("limits");

		assertThat(counter("financial.rate_limit.refused", "quota", "ingestion")).isEqualTo(1.0d);
		assertThat(counter("financial.rate_limit.refused", "quota", "limits")).isEqualTo(1.0d);
	}

	@Test
	@DisplayName("recording twice reuses the meter instead of making a second one")
	void repeatedCallsReuseTheMeter() {
		this.recorder.transactionAccepted("flagged");
		this.recorder.transactionAccepted("flagged");
		this.recorder.transactionAccepted("flagged");

		List<Meter> accepted = this.registry.getMeters().stream()
				.filter((meter) -> "financial.transactions.accepted".equals(meter.getId().getName())).toList();
		assertThat(accepted).hasSize(1);
		assertThat(accepted.getFirst().getId().getTags()).hasSize(1);
	}

	@Test
	@DisplayName("tag keys are closed, so a metric cannot be sliced by something unbounded")
	void tagKeysAreTheOnesADashboardGroupsBy() throws Exception {
		this.recorder.transactionAccepted("flagged");
		this.recorder.rateResolved("cache", "stub");
		this.recorder.limitInstalled("product");
		this.recorder.rateLimitRefused("ingestion");
		this.recorder.timeLookup("cache", () -> "rate");

		// The dimensions an operator actually slices by. A currency code or an account
		// number here
		// would not aggregate, and the cardinality would grow with every request rather
		// than with
		// every deployment.
		assertThat(tagKeysOf("financial.transactions.accepted")).containsExactly("outcome");
		assertThat(tagKeysOf("financial.rates.resolved")).containsExactlyInAnyOrder("source", "provider");
		assertThat(tagKeysOf("financial.limits.installed")).containsExactly("category");
		assertThat(tagKeysOf("financial.rate_limit.refused")).containsExactly("quota");
		assertThat(tagKeysOf("financial.rates.lookup")).containsExactly("source");
	}

	private List<String> tagKeysOf(String name) {
		return this.registry.find(name).meters().stream().flatMap(meter -> meter.getId().getTags().stream())
				.map((tag) -> tag.getKey()).toList();
	}

}
