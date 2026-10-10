package com.idfinance.financial_transactions.repository;

import com.idfinance.financial_transactions.entity.Currency;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

	/**
	 * Adds a currency unless the code is already there, and says whether it added
	 * one.
	 *
	 * <p>
	 * Idempotent at the database rather than at the application, and that is the
	 * only version that is true in general. An {@code existsByCode} check followed
	 * by a {@code save} is idempotent for one caller and wrong for two: several
	 * replicas start at once, every one of them sees the code missing, and the
	 * losers of that race fail on {@code ux_currency_code}. The write happens again
	 * in every deployment and in the weekly sweep, so "several callers" is the
	 * normal case rather than the corner one.
	 *
	 * <p>
	 * {@code do nothing} rather than an update, because the name is a display label
	 * and a provider spelling a currency differently is not a reason to rewrite
	 * what the seed or an earlier import put there.
	 *
	 * @return 1 when the row was inserted, 0 when the code was already present
	 */
	@Modifying
	@Query(value = """
			insert into currency (code, display_name)
			values (:code, :displayName)
			on conflict (code) do nothing
			""", nativeQuery = true)
	int insertIfAbsent(@Param("code") String code, @Param("displayName") String displayName);

}
