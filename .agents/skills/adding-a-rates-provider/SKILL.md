---
name: adding-a-rates-provider
description: Use when adding a new external exchange-rate provider, changing which provider is asked first, or touching app.rates.providers, RatesProvider, ChainedRatesProvider, or the conditions that register a provider. Also use before adding a field to AppProperties.Rates or editing a provider adapter.
---

# Adding a rates provider

Two edits and one feature file. That is the whole cost, and it is the reason the configuration
is shaped this way. If a change of that shape needs more than that, something has been written
into the wrong place — see **Where this used to break** below.

## The contract

| Concern | Where |
|---|---|
| What a provider must implement | `service/RatesProvider`: `rateFor` (returns `Optional<ExchangeRate>`), `name`, and optionally `publishedCurrencies` |
| Who asks whom, and in what order | `service/ChainedRatesProvider` — no edits needed |
| Whether any provider exists at all | `service/ProvidersConfigured` — no edits needed |
| Whether *this* provider is configured | `service/ConditionalOnProviderConfigured` |
| The settings | `AppProperties.Rates.Provider`, bound from the block |
| The adapter | `client/YourProvider.java` |

## Adding one

### 1. The block in `application.yml`

```yaml
    providers:
      twelvedata:
        base-url: ${RATES_API_BASE_URL:}
        api-key: ${RATES_API_KEY:}
        source: ${RATES_SOURCE:twelvedata}
        order: 10

      frankfurter:
        base-url: ${FRANKFURTER_API_BASE_URL:https://api.frankfurter.dev}
        order: 20

      yours:
        base-url: ${YOUR_RATES_URL:}
        api-key: ${YOUR_RATES_API_KEY:}
        order: 15          # between 10 and 20; nothing else moves
```

| Key | Required | Notes |
|---|---|---|
| `base-url` | yes | Empty means "not configured" — the adapter is simply absent. There is no `enabled` flag, because a second flag that can disagree with the first is not a feature. |
| `api-key` | no | **A provider without a key is still asked.** A self-hosted instance behind a proxy, an internal cache, a free tier that needs none. Leave it out; do not write an empty header. |
| `source` | no | What lands in `currency_rate.source`. Defaults to the block's key. Set it only when a deployment must be distinguishable in stored data — a sandbox, a different paid tier. |
| `order` | yes in practice | Lowest is asked first. The gaps are deliberate: `15` slots between `10` and `20` with no neighbour renumbered. A provider with no block sorts last. |

Then pass the new variables through `docker-compose.yml` **under `environment:`** and add them to
`.env.example`. A name added to `.env` and not to `environment:` is silently not passed — that is
how `FRANKFURTER_API_BASE_URL` went missing for a week.

### 2. The adapter

Copy `client/FrankfurterRatesProvider.java` — the simpler of the two, since it needs no inversion.

```java
@Component
@ConditionalOnProviderConfigured("yours")
@Slf4j
public final class YourProvider implements RatesProvider {

	private static final String PROVIDER = "yours";   // the block's key

	private final AppProperties properties;

	private final RestClient client;

	public YourProvider(AppProperties properties) {
		this.properties = properties;
		this.client = RestClient.builder().baseUrl(settings().baseUrl())
				.requestFactory(requestFactory(properties.rates())).build();
	}

	private AppProperties.Rates.Provider settings() {
		return this.properties.rates().providers().get(PROVIDER);
	}

	@Override
	public String name() {
		return settings().name();
	}
}
```

The field assignment in the constructor is not optional: `settings()` reads `this.properties`, so
an adapter that takes `AppProperties` as a parameter without keeping it does not compile, and an
adapter that keeps it under a different name breaks the moment a second provider is added beside it.

`name()` must return `settings().name()` — the block key, or the `source` override, whichever
`Rates.Rates` stamped onto the block. Returning a hardcoded literal here is the mistake the shared
constant was removed for, and it is currently made by exactly one adapter:
`FrankfurterRatesProvider.name()` returns `PROVIDER` rather than `settings().name()`, so a `source`
set on that block is ignored and the value stored in `currency_rate.source` is the block key. It is
latent rather than active — nothing sets `source` on that block today — which is the reason it is
worth knowing before copying that class. `TwelveDataRatesProvider` gets it right.

### 3. A scenario in `features/api/rates_fallback.feature`

One scenario per behaviour that is *yours* rather than the chain's: that the chain asks it, that
it answers in the orientation asked, that its absence falls through. The chain's own behaviour —
order, falling through on an absence **or on a failure**, the source carried on the rate — is
already covered and does not need repeating per provider.

**The suites pick their stubs on purpose.** `WireMockRatesConfiguration` (HTTP, one WireMock
server, both adapters pointed at it) is what exercises your request-building; `StubRatesProvider`
replaces the whole adapter and therefore exercises nothing about it. Add the scenario to the API
suite. `RatesFallbackSteps` is where the steps for the paid and fallback sides already live.

## Where this used to break

Four places used to have to be edited for every provider, and three of them failed silently when
forgotten. They are the reason for the shapes above; do not reintroduce them.

**The chain's condition named every provider.** It was

```java
@ConditionalOnExpression("'${app.rates.base-url:}' != '' or '${app.rates.frankfurter.base-url:}' != ''")
```

A third provider configured itself, registered its adapter, and was never asked, because nobody
added its property to a boolean expression on an unrelated class. Nothing refused to start: the
rate simply never resolved and the transaction was stored without a flag.
`ProvidersConfigured` now asks whether *anything* is configured, by enumerating
`app.rates.providers.*.base-url`.

**The order was `@Order` on the classes.** A provider slotted in between meant renumbering its
neighbours, and the numbering was the order the component scanner happened to produce otherwise.
A paid provider silently demoted to second costs money in a direction nobody chose. The order is
`order` in the block now.

**Every suite listed the providers.** Four `@TestPropertySource` lines blanked two URLs each. A
new provider meant four more lines, and forgetting one left a suite with a real adapter pointing at
the internet. It is `app.rates.providers-enabled=false` now, one line, and it cannot disagree with
anything: with it off, no block registers whatever it says.

**`@TestPropertySource` on an imported `@TestConfiguration` is not applied.** It was on
`WireMockRatesConfiguration`, so the URLs it set were ignored — and because Frankfurter has a
non-empty default in `application.yml` it registered and answered, against the *real* public
instance, while Twelve Data stayed absent. The suite reported a missing rate rather than a wrong
configuration. Properties for the API suite live on `ApiFeatureContext`, and the port lives in
`WireMockPort` because an annotation attribute cannot see a constant declared in the class the
annotation sits on.

## Checking it registered

A provider that never registers is the failure this configuration keeps trying to make hard to
produce, so verify rather than assume:

```bash
./mvnw test -Dtest=ApiFeatureTest                              # the stub answers, so the adapter is exercised
./mvnw verify                                                 # the whole build
```

If a scenario reports a rate the stub never sent, the adapter is not registered — not that the
stub is wrong. To see what registered, the condition reports its outcome at startup under
`logging.level.org.springframework.boot.autoconfigure=DEBUG`, naming the providers it found.

## Things about the real providers that stubs will not tell you

- **Twelve Data has no `KZT/USD`.** The symbol does not exist and answers 404 with "symbol or figi
  parameter is missing or invalid", which reads like a malformed request. `USD/KZT` is there, which
  is why that adapter asks the other side and divides.
- **There is no `/currency` endpoint.** `symbol_search` is the working source, and it answers
  equities and crypto too, so it is filtered on the LEDGER/OTHER shape and the other side is taken.
- **`previous_close` is not sent at all.** The answer carries exactly
  `datetime, open, high, low, close`. Reading that field produces null on every request, so the
  fallback is built by the adapter out of a second request over a window.
- **Frankfurter answers `1 base = rate quote`**, so KZT→USD is a fraction of a dollar (0.0022),
  not 450. Test data has to respect the orientation or the flag fails rather than the provider.
- **Frankfurter's `/v2/currencies` sends `end_date` on every row** — the last day it holds data for
  a code, not the day a currency was withdrawn. An adapter that filters rows by `end_date` discards
  the whole list and imports nothing; the log then reads "0 new codes of 0 published", which looks
  like a provider with nothing new. Retired codes (DEM, FRF, TRL, RUR) are simply absent from that
  endpoint — the safety comes from that, not from a filter.
- **A stub reproduces the contract the code assumes**; only the live provider tests the contract
  the code got. Everything above was found against Twelve Data with a real key, and none of it was
  visible to the suite.
