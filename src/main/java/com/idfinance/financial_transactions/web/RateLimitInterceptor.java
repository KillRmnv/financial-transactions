package com.idfinance.financial_transactions.web;

import com.idfinance.financial_transactions.logging.SafeLog;
import com.idfinance.financial_transactions.metrics.MetricsRecorder;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Hands out one permit per request for the handlers marked {@link RateLimited},
 * and refuses the ones whose limiter has no permit left.
 *
 * <p>
 * A refusal is thrown rather than written to the response: the interceptor has
 * no message converters, so building a {@code ProblemDetail} here would mean a
 * second, divergent copy of its shape. The single handler in the
 * {@code exception} package turns it into the 429 and sets {@code Retry-After}.
 *
 * <p>
 * The limiters are created by hand and cached per calling address rather than
 * taken from {@code @RateLimiter}. The annotation is the wrong tool for this:
 * resilience4j's aspect builds one limiter per method, so every caller shares
 * it, and the quota here is per address. The programmatic API is also what lets
 * a refusal be a {@code tryAcquirePermission} returning {@code false} — the
 * service then raises its own domain exception instead of resilience4j's
 * {@code RequestNotPermitted}, so the answer still comes from the single
 * handler.
 *
 * <p>
 * The limiters live in memory and are deliberately not shared between
 * instances: the quota protects this service from its own callers, and a shared
 * store would turn every transaction into a second database round trip. The
 * consequence is worth stating plainly — a horizontal scale-out multiplies the
 * effective quota by the number of instances, which is the wrong way round for
 * a global cap and the right way round for shedding load per node.
 */
@Component
@RequiredArgsConstructor
@Slf4j
final class RateLimitInterceptor implements HandlerInterceptor {

	private final Map<String, RateLimiter> limiters = new ConcurrentHashMap<>();

	private final RateLimitProperties rateLimitProperties;

	private final MetricsRecorder metricsRecorder;

	/**
	 * Whether an endpoint is limited, read on every request.
	 *
	 * <p>
	 * {@code getAnnotation} rather than
	 * {@code AnnotatedElementUtils.findMergedAnnotation}: the merged search walks
	 * meta-annotations and bridge methods to build a synthetic annotation out of
	 * several sources, and {@code @RateLimited} is used on a handler method or on a
	 * controller class and nowhere else. For the majority of requests the second
	 * answer is null, so this is one reflective lookup on a method that has no
	 * annotation — microseconds beside the JSON parsing and the database round trip
	 * the same request goes on to make.
	 *
	 * <p>
	 * An endpoint that is not limited is therefore one lookup, not a lookup per
	 * candidate. If a composed annotation for this is ever introduced, this has to
	 * become the merged search again, and the cost returns with it.
	 */
	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if (!(handler instanceof HandlerMethod method)) {
			return true;
		}
		RateLimited limits = method.getMethod().getAnnotation(RateLimited.class);
		if (limits == null) {
			limits = method.getBeanType().getAnnotation(RateLimited.class);
		}
		if (limits == null) {
			return true;
		}
		RateLimitProperties.Quota quota = resolve(limits);
		String key = bucketKey(request);
		RateLimiter limiter = this.limiters.computeIfAbsent(key, limiterKey -> build(limiterKey, quota));

		if (limiter.acquirePermission()) {
			return true;
		}
		// A refusal is worth a line: it is the one outcome that tells an operator a
		// caller is being
		// throttled, and it names the quota that ran out rather than just the address.
		this.metricsRecorder.rateLimitRefused(limits.quota());
		// The address is masked: a throttled caller is nearly always one caller
		// repeating
		// itself, and there is a way to group those lines without writing down a way to
		// find the person.
		log.warn("Refused {} {} from {}: the {} quota of {} permits per {} is exhausted", request.getMethod(),
				request.getRequestURI(), SafeLog.remoteAddress(request.getRemoteAddr()), limits.quota(),
				quota.limitForPeriod(), quota.limitRefreshPeriod());
		throw new RateLimitExceededException(key, quota.retryAfterSeconds());
	}

	/**
	 * The configuration block wins over the annotation defaults, and the annotation
	 * defaults are the fallback so that adding a new limited endpoint never
	 * silently means "unlimited".
	 */
	private RateLimitProperties.Quota resolve(RateLimited limits) {
		RateLimitProperties.Quota configured = this.rateLimitProperties.rateLimits().get(limits.quota());
		if (configured == null) {
			return new RateLimitProperties.Quota(limits.limitForPeriod(),
					Duration.ofMillis(limits.limitRefreshPeriodMillis()), limits.retryAfterSeconds());
		}
		return configured;
	}

	/**
	 * @param key
	 *            names the limiter after the address it belongs to, which is what
	 *            makes a thread dump or a metric readable; the map key alone would
	 *            not show up in either
	 */
	private static RateLimiter build(String key, RateLimitProperties.Quota quota) {
		RateLimiterConfig config = RateLimiterConfig.custom().limitForPeriod(quota.limitForPeriod())
				.limitRefreshPeriod(quota.limitRefreshPeriod())
				// A refused request must be refused immediately: waiting here would hold a
				// servlet thread
				// for a permit the caller is not going to be told about until it arrives.
				.timeoutDuration(Duration.ZERO).build();
		return RateLimiter.of(key, config);
	}

	/**
	 * One limiter per calling address.
	 *
	 * <p>
	 * The obvious key would be the account, and it was: the account is what the
	 * specification is written about, so one noisy client must not be able to
	 * exhaust anyone else's budget. It does not work from here, though. The account
	 * arrives in the JSON body, and an interceptor runs before the controller has
	 * read it — {@code getParameter} does not see a body, and consuming the stream
	 * to look would leave nothing to parse. Caching the body first does not help
	 * either, because the cache fills as the controller reads.
	 *
	 * <p>
	 * So the quota is per address, and a per-account one needs either a filter that
	 * buffers the body before the interceptor runs, or an account header the client
	 * sends alongside. Either is a deliberate change, not something to smuggle in
	 * here.
	 */
	private static String bucketKey(HttpServletRequest request) {
		return "ip=" + request.getRemoteAddr();
	}

}
