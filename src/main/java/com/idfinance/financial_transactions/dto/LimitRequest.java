package com.idfinance.financial_transactions.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;

/**
 * Payload for installing a limit.
 *
 * <p>
 * There is deliberately no date field: the specification states the service
 * always sets the current time itself, which is what makes a client unable to
 * backdate or postdate a limit.
 */
@Schema(description = "A new monthly limit. Installing appends; updating an existing one is forbidden.")
public record LimitRequest(

		@NotBlank @Pattern(regexp = "^[0-9]{10}$") @Schema(description = "The account the limit applies to", example = "0000000123") String accountFrom,

		@NotBlank @Schema(description = "Exactly `product` or `service`", example = "product", allowableValues = {
				"product", "service"}) String expenseCategory,

		@NotNull @DecimalMin(value = "0.00", inclusive = false) @Digits(integer = 12, fraction = 2) @Schema(description = "The limit, always in the ledger currency. There is no currency field because there is no choice to make", example = "2000.00") BigDecimal sum) {

}
