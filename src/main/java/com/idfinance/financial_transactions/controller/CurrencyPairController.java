package com.idfinance.financial_transactions.controller;

import com.idfinance.financial_transactions.dto.CurrencyPairRequest;
import com.idfinance.financial_transactions.dto.CurrencyPairResponse;
import com.idfinance.financial_transactions.mapper.CurrencyPairMapper;
import com.idfinance.financial_transactions.service.CurrencyPairService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Which currencies the daily sweep keeps warm.
 *
 * <p>
 * An operations API rather than a client one, and it is not part of the two the
 * specification asks for. It exists because the alternative is changing a table
 * with SQL, and the specification's prototype carries no access control at all
 * — so on its own this endpoint is not a new risk, but it is the first thing to
 * remove before this goes anywhere shared.
 *
 * <p>
 * Nothing here is a rejection. A currency absent from this set is still
 * accepted in a transaction and its rate is fetched on demand; this only says
 * what has already been pre-fetched, at one paid request a day each.
 */
@RestController
@RequestMapping("/api/v1/currency-pairs")
@RequiredArgsConstructor
@Tag(name = "Currency pairs", description = "Operations on the set of currencies whose rates the daily sweep pre-fetches.")
public final class CurrencyPairController {

	private final CurrencyPairService currencyPairService;

	private final CurrencyPairMapper currencyPairMapper;

	@Operation(summary = "List the tracked currencies", description = """
			Every currency the service has decided to track, active or not. An inactive currency is
			not rejected in a transaction: its rate is fetched on demand from the moment it is
			deactivated, and the stored rates stay as history.""")
	@ApiResponse(responseCode = "200", description = "The tracked currencies")
	@GetMapping
	List<CurrencyPairResponse> findAll() {
		return this.currencyPairService.all().stream().map(this.currencyPairMapper::toResponse).toList();
	}

	@Operation(summary = "Start or stop sweeping a currency", description = """
			Moves a currency in or out of the daily sweep. Activating one that is not tracked adds
			it; deactivating keeps the row and the rates already fetched.

			Setting the value it already has is a success rather than a conflict, so a retried
			request does not fail.""")
	@ApiResponse(responseCode = "200", description = "The currency is now tracked as requested")
	@ApiResponse(responseCode = "400", description = "The code is not three letters, or the body has no active flag", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "The currency is not in the lookup, so tracking it would sweep a currency that cannot be priced", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
	@PutMapping("/{code}")
	ResponseEntity<CurrencyPairResponse> setActive(
			@Parameter(description = "ISO 4217 code, in any case", example = "KZT") @PathVariable("code") String code,
			@Valid @RequestBody CurrencyPairRequest request) {

		var pair = request.active()
				? this.currencyPairService.activate(code)
				: this.currencyPairService.deactivate(code);

		return ResponseEntity.status(HttpStatus.OK).body(this.currencyPairMapper.toResponse(pair));
	}

}
