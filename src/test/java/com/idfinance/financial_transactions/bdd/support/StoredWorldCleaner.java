package com.idfinance.financial_transactions.bdd.support;

import com.idfinance.financial_transactions.config.RateCachePolicy;
import com.idfinance.financial_transactions.repository.CurrencyRateRepository;
import com.idfinance.financial_transactions.repository.ExpenseLimitRepository;
import com.idfinance.financial_transactions.repository.ExpenseTransactionRepository;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Returns a suite's world to the state the next scenario expects to find.
 *
 * <p>
 * Every suite wipes the same four things before each scenario, and they used to
 * do it in their own {@code @Before}. That was four copies of a list that has
 * to be right in every one of them, and adding the cache made the mistake
 * visible: the database was emptied while the cached rates survived, so a
 * scenario asking for "a currency with no rate anywhere" was answered from a
 * rate the previous scenario had put there. The rows were gone; the answer was
 * not.
 *
 * <p>
 * The cache is cleared by key prefix rather than by flushing the database,
 * because the prefix is what this service owns in a Redis that tests happen to
 * have to themselves.
 */
@Component
public class StoredWorldCleaner {

	private final ExpenseTransactionRepository expenseTransactionRepository;

	private final ExpenseLimitRepository expenseLimitRepository;

	private final CurrencyRateRepository currencyRateRepository;

	private final StringRedisTemplate redisTemplate;

	private final RateCachePolicy rateCachePolicy;

	public StoredWorldCleaner(ExpenseTransactionRepository expenseTransactionRepository,
			ExpenseLimitRepository expenseLimitRepository, CurrencyRateRepository currencyRateRepository,
			StringRedisTemplate redisTemplate, RateCachePolicy rateCachePolicy) {
		this.expenseTransactionRepository = expenseTransactionRepository;
		this.expenseLimitRepository = expenseLimitRepository;
		this.currencyRateRepository = currencyRateRepository;
		this.redisTemplate = redisTemplate;
		this.rateCachePolicy = rateCachePolicy;
	}

	/**
	 * The policy in force, so a scenario that writes into the cache uses the same
	 * prefix the service does rather than repeating it.
	 */
	public RateCachePolicy rateCachePolicy() {
		return this.rateCachePolicy;
	}

	/**
	 * Order matters only for readability here — the tables do not reference each
	 * other in a way that makes this a graph — but the rates go last, because they
	 * are what the transactions were converted with and a reader should not have to
	 * reason about which order is safe.
	 */
	public void clean() {
		this.expenseTransactionRepository.deleteAll();
		this.expenseLimitRepository.deleteAll();
		this.currencyRateRepository.deleteAll();
		cleanCache();
	}

	/**
	 * The generation counters included: bumping them is what retiring the cached
	 * rates amounts to, and deleting only the dated keys would leave the counters
	 * behind for the next scenario to inherit.
	 */
	public void cleanCache() {
		if (!this.rateCachePolicy.enabled()) {
			return;
		}
		Set<String> keys = this.redisTemplate.keys(this.rateCachePolicy.keyPrefix() + "*");
		if (keys != null && !keys.isEmpty()) {
			this.redisTemplate.delete(keys);
		}
	}

}
