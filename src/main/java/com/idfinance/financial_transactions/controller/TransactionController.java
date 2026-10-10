package com.idfinance.financial_transactions.controller;

import com.idfinance.financial_transactions.config.LedgerPolicy;
import com.idfinance.financial_transactions.domain.MonthBoundary;
import com.idfinance.financial_transactions.dto.TransactionRequest;
import com.idfinance.financial_transactions.dto.TransactionResponse;
import com.idfinance.financial_transactions.mapper.TransactionMapper;
import com.idfinance.financial_transactions.service.TransactionService;
import com.idfinance.financial_transactions.web.RateLimited;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ingestion API, meant for the bank's systems rather than for clients.
 *
 * <p>
 * The controller only validates and delegates. Everything else, including the
 * {@code limit_exceeded} decision, belongs to the service layer, and the
 * response body comes from MapStruct.
 */
@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
@Tag(name = "Transactions", description = "Ingestion of expense operations from the banking systems.")
public final class TransactionController {

	private final TransactionService transactionService;

	private final TransactionMapper transactionMapper;

	private final MonthBoundary monthBoundary;

	/**
	 * Says which currency {@code sumInLedger} is in, and at what scale an amount is
	 * presented. Both are read from configuration rather than guessed from the
	 * request: a transaction's own currency is not the one its converted amount is
	 * expressed in, and the digits a client is shown are not the digits the row
	 * stores.
	 */
	private final LedgerPolicy ledgerPolicy;

	@Operation(summary = "Accept a batch of expense operations", description = """
			Stores several transactions in one call. Their exchange rates are resolved concurrently,
			because a currency the service has never seen costs an external call measured in seconds, and
			a client's transactions for a day are usually spread across several of them.

			Each transaction is still flagged one at a time under the limit's row lock, so a batch does
			not get a different answer than the same transactions sent individually: the running total is
			read after the previous one has committed, in the order the request lists them.

			A transaction whose currency has no available rate is stored with `limitCalculated` false
			rather than rejected. A failure that is not about rates — an unknown currency — rejects the
			whole batch.""")
	@ApiResponse(responseCode = "201", description = "Every transaction stored, in the order it was sent")
	@ApiResponse(responseCode = "400", description = "The body failed validation, or the batch was empty or too large", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "Unknown category or currency in one of the transactions", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "429", description = "The calling address exhausted its request quota", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "503", description = "The rates for the batch could not be obtained in time; nothing was stored, so the batch may be sent again", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
	@RateLimited(quota = "ingestion")
	@PostMapping("/batch")
	ResponseEntity<List<TransactionResponse>> acceptBatch(
			@Valid @RequestBody @NotEmpty @Size(max = 100) List<@Valid TransactionRequest> requests) {
		var accepted = this.transactionService.acceptBatch(requests);
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(accepted.stream().map(
						row -> this.transactionMapper.toResponse(row, this.monthBoundary.zone(), this.ledgerPolicy))
						.toList());
	}

	@Operation(summary = "Accept an expense operation", description = """
			Stores the transaction, converts it to USD and flags it when the month's total goes over
			the limit in effect on the transaction date. The response reports whether the flag could be
			computed at all: a currency with no available rate is stored with `limitCalculated` false
			rather than being rejected or guessed at.""")
	@ApiResponse(responseCode = "201", description = "Stored")
	@io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "kztTransaction", summary = "A tenge expense", value = "{\"accountFrom\":\"0000000123\",\"accountTo\":\"9999999999\",\"currencyShortname\":\"KZT\","
			+ "\"sum\":10000.45,\"expenseCategory\":\"product\",\"datetime\":\"2022-01-30T00:00:00+06:00\"}")))
	@ApiResponse(responseCode = "400", description = "The body failed validation", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "Unknown category or currency", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "429", description = "The calling address exhausted its request quota", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
	@RateLimited(quota = "ingestion")
	@PostMapping
	ResponseEntity<TransactionResponse> accept(@Valid @RequestBody TransactionRequest request) {
		var accepted = transactionService.accept(request);
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(transactionMapper.toResponse(accepted, this.monthBoundary.zone(), this.ledgerPolicy));
	}
}
