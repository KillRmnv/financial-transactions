package com.idfinance.financial_transactions.service;

import com.idfinance.financial_transactions.entity.ExpenseTransaction;

/**
 * What the ingestion service returns to the web layer.
 *
 * <p>
 * The category and currency codes travel alongside the saved transaction
 * because the controller must not look them up: reaching into a repository from
 * a controller is what the architecture tests forbid, and it would mean
 * resolving the same row twice for one request.
 *
 */
public record AcceptedTransaction(ExpenseTransaction transaction, String expenseCategory, String currencyCode) {
}
