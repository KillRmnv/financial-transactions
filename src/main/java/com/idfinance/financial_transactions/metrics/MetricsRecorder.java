package com.idfinance.financial_transactions.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.Callable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Every business number this service reports, in one place.
 *
 * <p>
 * Meters are looked up from the registry by name on each call rather than held
 * in fields. The registry caches by name and tags, so this is a map lookup and
 * not a new meter, and it buys the only thing that matters here: a metric name
 * exists in exactly one file, so a dashboard cannot disagree with the code
 * about what it counts.
 *
 * <p>
 * Tag values are closed sets on purpose. A metric tagged with a currency code
 * or an account number does not aggregate, and the cardinality grows without
 * bound; the dimensions below are the ones an operator actually slices by.
 */
@Component
@RequiredArgsConstructor
public final class MetricsRecorder {

	private static final String ACCEPTED = "financial.transactions.accepted";

	private static final String RATES_RESOLVED = "financial.rates.resolved";

	private static final String RATE_LOOKUP = "financial.rates.lookup";

	private static final String SWEEP_DURATION = "financial.rates.sweep.duration";

	private static final String SWEEP_CURRENCIES = "financial.rates.sweep.currencies";

	private static final String LIMITS_INSTALLED = "financial.limits.installed";

	private static final String RATE_LIMIT_REFUSED = "financial.rate_limit.refused";

	private final MeterRegistry meterRegistry;

	/**
	 * @param outcome
	 *            {@code flagged}, {@code within_limit} or {@code uncalculated} --
	 *            the third is not a flavour of false, it is the absence of an
	 *            answer, and merging it with the others would hide exactly the case
	 *            an operator needs to see
	 */
	public void transactionAccepted(String outcome) {
		Counter.builder(ACCEPTED).tag("outcome", outcome).register(this.meterRegistry).increment();
	}

	/**
	 * @param source
	 *            where the rate came from: {@code cache}, {@code database} or
	 *            {@code external}. The split is the point -- it says how often the
	 *            service is paying for a call it already had
	 * @param provider
	 *            the provider that answered, so a failover is visible in the
	 *            numbers
	 */
	public void rateResolved(String source, String provider) {
		Counter.builder(RATES_RESOLVED).tag("source", source).tag("provider", provider).register(this.meterRegistry)
				.increment();
	}

	/**
	 * Times the lookup chain, which is where a slow upstream turns into a slow
	 * ingestion path.
	 */
	public <T> T timeLookup(String source, Callable<T> lookup) throws Exception {
		return Timer.builder(RATE_LOOKUP).tag("source", source).publishPercentileHistogram()
				.register(this.meterRegistry).recordCallable(lookup);
	}

	/**
	 * Records the outcome of one sweep.
	 *
	 * <p>
	 * <strong>Two meters, two names.</strong> They used to share one, and
	 * Micrometer accepts it — the Prometheus exposition then emits
	 * {@code financial_rates_sweep_total} for the counter and
	 * {@code financial_rates_sweep_seconds_*} for the timer, verified against the
	 * real registry. It works and it is a trap: a dashboard querying
	 * {@code financial_rates_sweep} gets neither series whole, and an empty panel
	 * is indistinguishable from a sweep that never ran. That is the same failure
	 * Micrometer's own {@code hikaricp.*} prefix causes, and it is silent.
	 *
	 * @param duration
	 *            wall clock time of the whole sweep
	 * @param currencies
	 *            how many currencies were refreshed
	 */
	public void sweepCompleted(Duration duration, int currencies) {
		Timer.builder(SWEEP_DURATION).publishPercentileHistogram().register(this.meterRegistry)
				.record(duration.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
		Counter.builder(SWEEP_CURRENCIES).register(this.meterRegistry).increment(currencies);
	}

	public void limitInstalled(String category) {
		Counter.builder(LIMITS_INSTALLED).tag("category", category).register(this.meterRegistry).increment();
	}

	/**
	 * @param quota
	 *            the {@code app.rate-limits} block that refused, so a throttled
	 *            caller can be traced to the endpoint rather than to the address
	 */
	public void rateLimitRefused(String quota) {
		Counter.builder(RATE_LIMIT_REFUSED).tag("quota", quota).register(this.meterRegistry).increment();
	}

}
