package com.idfinance.financial_transactions.client;

import com.idfinance.financial_transactions.domain.ExchangeRate;
import com.idfinance.financial_transactions.service.RatesProvider;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Stand-in used when no provider is configured.
 */
@Component
@ConditionalOnMissingBean(RatesProvider.class)
public final class NoRatesProvider implements RatesProvider {

	@Override
	public ExchangeRate rateFor(String baseCurrency, String quoteCurrency, java.time.LocalDate date) {
		return null;
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
