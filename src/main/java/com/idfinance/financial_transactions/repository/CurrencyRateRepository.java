package com.idfinance.financial_transactions.repository;

import com.idfinance.financial_transactions.entity.Currency;
import com.idfinance.financial_transactions.entity.CurrencyRate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Rates for one base currency, stored against the ledger currency.
 *
 * <p>
 * Only the base side is stored. The quote is whatever {@code LedgerPolicy}
 * names, and persisting a copy of it would give two rows that can disagree the
 * day the ledger moves.
 */
public interface CurrencyRateRepository extends JpaRepository<CurrencyRate, Long> {

	/**
	 * The rate of one exact day, which is what the lookup chain tries first.
	 *
	 * <p>
	 * Written as an explicit query rather than derived from the parameter names:
	 * the properties are {@code baseCurrency} and {@code rateDate}, and a derived
	 * query would look for properties called {@code currency} and {@code date},
	 * which do not exist.
	 */
	@Query("""
			select r from CurrencyRate r
			where r.baseCurrency = :currency
			  and r.rateDate = :date
			""")
	Optional<CurrencyRate> findForDay(@Param("currency") Currency currency, @Param("date") LocalDate date);

	/**
	 * The most recent rate not later than the requested day, used when that day has
	 * no close of its own yet.
	 */
	@Query("""
			select r from CurrencyRate r
			where r.baseCurrency = :currency
			  and r.rateDate <= :date
			order by r.rateDate desc
			""")
	Optional<CurrencyRate> findLatestOnOrBefore(@Param("currency") Currency currency, @Param("date") LocalDate date);

	/**
	 * Writes the rate for a pair and day, or refreshes the one already there.
	 *
	 * <p>
	 * One statement rather than a find-then-write, and that is not a style
	 * preference: two transactions can legitimately reach here with the same pair
	 * and day. A batch resolves its rates in parallel, so three KZT transactions
	 * asked for together share one provider call — the coordinator hands them the
	 * same {@code ExchangeRate} — and all three then store it, each in a
	 * transaction of its own. With a find-then-write the first inserts and the
	 * other two hit {@code ux_currency_rate_base_date} and are marked
	 * rollback-only, which surfaces as {@code UnexpectedRollbackException} from a
	 * commit rather than as the constraint violation that actually happened.
	 *
	 * <p>
	 * {@code do update} rather than {@code do nothing} because a refresh is the
	 * point: a provider republishing a corrected close for the same day must
	 * overwrite the value it published before.
	 *
	 * @return 1 whether a new row was inserted or an existing one refreshed; there
	 *         is no outcome that leaves the pair unchanged
	 */
	@Modifying
	@Query(value = """
			insert into currency_rate (base_currency, rate_date, close, previous_close, source)
			select :currencyCode, :rateDate, :close, :previousClose, :source
			on conflict (base_currency, rate_date) do update
			    set close = excluded.close,
			        previous_close = excluded.previous_close,
			        fetched_at = now()
			""", nativeQuery = true)
	int upsert(@Param("currencyCode") String currencyCode, @Param("rateDate") LocalDate rateDate,
			@Param("close") BigDecimal close, @Param("previousClose") BigDecimal previousClose,
			@Param("source") String source);

}
