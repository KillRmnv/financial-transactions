package com.idfinance.financial_transactions.controller;

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
	// The numbers live under app.rate-limits.ingestion; this only marks the
	// endpoint as limited, and its own
	// values are the fallback. The quota is per calling address, not per account —
	// see RateLimitInterceptor for
	// why the account is not available here.
	@RateLimited(quota = "ingestion")
	@PostMapping
	ResponseEntity<TransactionResponse> accept(@Valid @RequestBody TransactionRequest request) {
		var accepted = transactionService.accept(request);
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(transactionMapper.toResponse(accepted, this.monthBoundary.zone()));
	}

}
