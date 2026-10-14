package com.idfinance.financial_transactions.client;

import com.idfinance.financial_transactions.domain.ExchangeRate;
import com.idfinance.financial_transactions.service.RatesProvider;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Stand-in used when no provider is configured.
 */
@Component
@ConditionalOnMissingBean(RatesProvider.class)
public final class NoRatesProvider implements RatesProvider {

	@Override
	public Optional<ExchangeRate> rateFor(String baseCurrency, String quoteCurrency, LocalDate date) {
		// Empty rather than an exception: a deployment with no provider configured has
		// no
		// rate for anything, and that is a working configuration, not a failure to log.
		return Optional.empty();
	}

	@Override
	public String name() {
		return "none";
	}

	@Override
	public List<PublishedCurrency> publishedCurrencies() {
		return List.of();
	}

}
