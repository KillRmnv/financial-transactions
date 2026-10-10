package com.idfinance.financial_transactions.repository;

import com.idfinance.financial_transactions.entity.CurrencyPair;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The set of currencies the daily sweep keeps warm.
 *
 * <p>
 * Reading is one method; changing the set is the service's business, because a
 * row has to be created before it can be deactivated and the currency has to
 * exist in the lookup before the row can reference it.
 */
public interface CurrencyPairRepository extends JpaRepository<CurrencyPair, Long> {

	List<CurrencyPair> findByActiveTrueOrderByBaseCurrencyCodeAsc();

	/**
	 * Written out rather than derived: the field is {@code baseCurrency}, an
	 * entity, and the column it maps to is the currency's natural key. A derived
	 * name here would be {@code findByBaseCurrency_Code}, which reads as a typo.
	 */
	@Query("""
			select p from CurrencyPair p
			where p.baseCurrency.code = :code
			""")
	Optional<CurrencyPair> findByBaseCurrencyCode(@Param("code") String code);

	List<CurrencyPair> findAllByOrderByBaseCurrencyCodeAsc();

}
