package com.idfinance.financial_transactions.repository;

import com.idfinance.financial_transactions.entity.Currency;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The verified ISO 4217 lookup.
 *
 * <p>
 * Only the methods the application actually calls are declared here:
 * {@code JpaRepository} already provides the rest, and speculative query
 * methods are just untested code that will rot.
 */
public interface CurrencyRepository extends JpaRepository<Currency, Long> {

	Optional<Currency> findByCode(String code);

	boolean existsByCode(String code);

}
