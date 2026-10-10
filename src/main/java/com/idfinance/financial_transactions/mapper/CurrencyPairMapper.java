package com.idfinance.financial_transactions.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/**
 * Entity to DTO for the tracked currency pairs.
 *
 * <p>
 * Every target is named, including the one that already matches: an implicit
 * mapping stops working silently the day a field is renamed, and the compiler
 * says nothing about it.
 *
 * <p>
 * {@code code} is not a column of {@code currency_pair} — it is the natural key
 * of the {@code Currency} the row references, so it has to be dug out of the
 * relation rather than read off the entity.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface CurrencyPairMapper {

	@Mapping(target = "code", source = "pair.baseCurrency.code")
	@Mapping(target = "active", source = "pair.active")
	@Mapping(target = "createdAt", source = "pair.createdAt")
	com.idfinance.financial_transactions.dto.CurrencyPairResponse toResponse(
			com.idfinance.financial_transactions.entity.CurrencyPair pair);

}
