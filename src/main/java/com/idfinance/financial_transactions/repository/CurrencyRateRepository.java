package com.idfinance.financial_transactions.repository;

import com.idfinance.financial_transactions.entity.Currency;
import com.idfinance.financial_transactions.entity.CurrencyRate;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CurrencyRateRepository extends JpaRepository<CurrencyRate, Long> {

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

}
