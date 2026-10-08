package com.idfinance.financial_transactions.repository;

import com.idfinance.financial_transactions.entity.ExpenseCategory;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExpenseCategoryRepository extends JpaRepository<ExpenseCategory, Long> {

	Optional<ExpenseCategory> findByCode(String code);

}
