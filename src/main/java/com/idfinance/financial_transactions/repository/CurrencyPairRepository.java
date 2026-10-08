package com.idfinance.financial_transactions.repository;

import com.idfinance.financial_transactions.entity.CurrencyPair;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Only the active pairs, which is the whole point of the table: the set the
 * daily scheduler sweeps.
 */
public interface CurrencyPairRepository extends JpaRepository<CurrencyPair, Long> {

	List<CurrencyPair> findByActiveTrueOrderByBaseCurrencyCodeAsc();

}
