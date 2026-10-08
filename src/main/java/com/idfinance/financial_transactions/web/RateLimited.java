package com.idfinance.financial_transactions.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that a handler is subject to a per-caller request quota.
 *
 * <p>
 * The numbers here are defaults, not the source of truth. {@link #quota()}
 * names a block under {@code app.rate-limits} where the real values live, so a
 * limit can be retuned without a rebuild and a test can shrink it to something
 * it can actually exhaust. The annotation only has to be present for the
 * endpoint to be limited at all.
 *
 * <p>
 * Note that this is not {@code @RateLimiter}: that annotation is an AOP aspect
 * that builds one limiter per method, which is a per-method quota and not the
 * per-caller one wanted here. See {@code RateLimitInterceptor} for the
 * programmatic equivalent and the reasoning.
 *
 * <p>
 * The limiter is per calling address. A per-account one would be nicer, and the
 * account is what the specification is written about, but it travels in the
 * request body and an interceptor cannot see a body it has not consumed — see
 * {@code RateLimitInterceptor.bucketKey} for the full reasoning.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RateLimited {

	/**
	 * @return the key of the {@code app.rate-limits} block holding the permit
	 *         count, the refresh period and the retry delay. When no such block
	 *         exists, the values declared below are used as they are.
	 */
	String quota() default "";

	/**
	 * @return how many requests are allowed per {@link #limitRefreshPeriodMillis()}
	 */
	int limitForPeriod() default 100;

	/**
	 * @return the period the allowance is counted over, in milliseconds. A period
	 *         rather than a rate, because that is the unit a limiter is configured
	 *         in: "10 per second" and "600 per minute" are the same policy
	 *         expressed differently, and a fractional rate would have to be rounded
	 *         into a whole permit count somewhere. Milliseconds rather than a
	 *         Duration because an annotation default must be a compile-time
	 *         constant.
	 */
	long limitRefreshPeriodMillis() default 1000;

	/**
	 * @return seconds to wait before answering 429, surfaced as {@code Retry-After}
	 */
	long retryAfterSeconds() default 1;

}
