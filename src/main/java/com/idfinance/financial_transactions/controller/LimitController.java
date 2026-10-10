package com.idfinance.financial_transactions.controller;

import com.idfinance.financial_transactions.config.LedgerPolicy;
import com.idfinance.financial_transactions.dto.LimitRequest;
import com.idfinance.financial_transactions.dto.LimitResponse;
import com.idfinance.financial_transactions.dto.TransactionWithLimitResponse;
import com.idfinance.financial_transactions.mapper.LimitMapper;
import com.idfinance.financial_transactions.mapper.TransactionMapper;
import com.idfinance.financial_transactions.service.LimitService;
import com.idfinance.financial_transactions.service.ReportingService;
import com.idfinance.financial_transactions.service.TransactionService;
import com.idfinance.financial_transactions.web.RateLimited;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Client-facing API: install a limit, list limits, list the transactions that
 * exceeded one, and publish the accepted currency codes.
 *
 * <p>
 * No business logic here, no repository access either, and no hand-built
 * response objects: reads go through {@link ReportingService} and every DTO
 * comes out of {@link TransactionMapper} or {@link LimitMapper}.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Limits", description = "Client-facing operations on monthly spend limits.")
public final class LimitController {

	private final LimitService limitService;

	private final ReportingService reportingService;

	private final LimitMapper limitMapper;

	/**
	 * Read from configuration rather than derived from a row: the digits a client
	 * is shown are a presentation decision, and the row keeps the precision it was
	 * given.
	 */
	private final LedgerPolicy ledgerPolicy;

	private final TransactionService transactionService;

	private final TransactionMapper transactionMapper;

	@Operation(summary = "Install a new limit", description = """
			Adds a limit for one account and category. The installation time is always the current
			moment: there is no date in the body, which is what makes backdating impossible. A client
			may install as many limits as it likes; only updating an existing one is forbidden.""")
	@ApiResponse(responseCode = "201", description = "Installed")
	@io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "raiseTheJanuaryLimit", summary = "Raising the limit to 2000", value = "{\"accountFrom\":\"0000000123\",\"expenseCategory\":\"product\",\"sum\":2000.00}")))
	@ApiResponse(responseCode = "400", description = "The body failed validation", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "Unknown category", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "429", description = "The calling address exhausted its request quota", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
	@RateLimited(quota = "limit-install")
	@PostMapping("/limits")
	ResponseEntity<LimitResponse> install(@Valid @RequestBody LimitRequest request) {
		String accountFrom = request.accountFrom();
		var installed = limitService.install(request);

		LimitResponse body = reportingService.limitRowOf(accountFrom, installed.getId())
				.map(row -> this.limitMapper.toResponse(accountFrom, row, this.ledgerPolicy)).orElseThrow();

		return ResponseEntity.status(HttpStatus.CREATED).body(body);
	}

	@Operation(summary = "List the limits of an account", description = "Returns every limit installed for the account, each with what has been spent "
			+ "against it in the current month.")
	@ApiResponse(responseCode = "200", description = "The limits, newest first")
	@GetMapping("/limits")
	List<LimitResponse> findAll(
			@Parameter(description = "The account to list limits for", example = "0000000123") @RequestParam("account_from") String accountFrom) {
		return reportingService.limitsOf(accountFrom).stream()
				.map(row -> this.limitMapper.toResponse(accountFrom, row, this.ledgerPolicy)).toList();
	}

	@Operation(summary = "List the transactions that exceeded the limit", description = """
			Each row carries `limitSum`, `limitDatetime` and `limitCurrencyShortname` for the limit the
			flag was actually computed against, which is not necessarily the limit installed today.
			`limitDatetime` is null for the automatic 1000 USD fallback, which has no installation
			event of its own.""")
	@ApiResponse(responseCode = "200", description = "The exceeded transactions, oldest first")
	@GetMapping("/limits/exceeded")
	List<TransactionWithLimitResponse> exceeded(
			@Parameter(description = "The account to report on", example = "0000000123") @RequestParam("account_from") String accountFrom,

			@Parameter(description = "First day of the month to report. Defaults to the current month; any day of the month is accepted and normalised", example = "2022-01-01") @RequestParam(name = "month", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate month) {
		return reportingService.exceededInMonth(accountFrom, month).stream().map(this::toResponse).toList();
	}

	@Operation(summary = "List the accepted currency codes", description = """
			The codes a transaction may use. A client refused with 404 for an unknown currency reads the
			accepted set from here instead of guessing at spellings; every code is uppercase.""")
	@ApiResponse(responseCode = "200", description = "ISO 4217 codes, sorted")
	@GetMapping("/currencies")
	List<String> currencies() {
		return transactionService.supportedCurrencies();
	}

	private TransactionWithLimitResponse toResponse(ReportingService.ExceededRow row) {
		return transactionMapper.toResponseWithLimit(row.transaction(), row.accountFrom(), row.currencyCode(),
				row.expenseCategory(), row.zone(), row.limitDatetime(), row.limitSum(), row.limitCurrencyShortname(),
				this.ledgerPolicy);
	}

}
