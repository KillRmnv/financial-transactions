package com.idfinance.financial_transactions.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Fills the currency lookup once the service is up.
 *
 * <p>
 * The migration seeds the ledger currency and the two the specification names,
 * and nothing else. This is what makes the rest of the world available: the
 * codes the provider publishes are added on the way up rather than being
 * written out as 157 rows of ISO 4217 in a migration that should describe a
 * schema. {@code WeeklyCurrencyImport} then keeps it current.
 *
 * <p>
 * <strong>It does not hold the startup.</strong> The event fires once the
 * context is ready, and the import is handed to a virtual thread rather than
 * run on the one that publishes it. Tomcat is already accepting connections by
 * then, and the currencies the specification names are already accepted; a
 * provider that is slow would otherwise decide whether the service starts at
 * all, which is the one dependency this project is built not to have — a
 * transaction in KZT does not need a rate to be stored, and a cold start must
 * not turn a provider outage into an outage here.
 *
 * <p>
 * A transaction in a currency this has not added yet is refused with 404 for
 * the few seconds it takes. That is the same answer it would get on any
 * deployment that never ran an import, and it is the honest one: the lookup is
 * a verified reference, and an unverified code does not belong in it.
 *
 * <p>
 * No condition on the provider bean on purpose. {@code @ConditionalOnBean} is
 * settled in scan order and disappears entirely without one, which is a class
 * of failure that shows up only in the configuration nobody tests;
 * {@link NoRatesProvider} answers with an empty list instead, so asking it
 * costs nothing and there is nothing to get wrong.
 */
@Component
@ConditionalOnProperty(name = "app.scheduling.enabled", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public final class CurrencyBootstrap {

	private final CurrencyImporter currencyImporter;

	@EventListener(ApplicationReadyEvent.class)
	public void fillTheLookupAtStartup() {
		Thread.ofVirtual().name("currency-bootstrap").start(() -> {
			try {
				this.currencyImporter.importPublished();
			} catch (RuntimeException ex) {
				// The importer already handles the provider failing. Anything reaching here is
				// a defect, and it must not take the process down: the service is up and
				// serving the codes it was seeded with.
				log.warn("The startup currency import failed: {}", ex.getMessage());
			}
		});
	}

}
