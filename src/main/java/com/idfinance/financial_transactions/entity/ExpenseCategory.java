package com.idfinance.financial_transactions.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Expense category. Modelled as a lookup table rather than a Java enum so the
 * database carries a foreign key and the list of categories is visible to
 * anyone inspecting it.
 */
@Entity
@Table(name = "expense_category")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public final class ExpenseCategory {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "code", nullable = false, length = 32)
	private String code;

	@Column(name = "display_name", nullable = false, length = 128)
	private String displayName;

	@Override
	public String toString() {
		return this.code;
	}

}
