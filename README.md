# Financial Transactions

Microservice for monthly USD spend limits: it accepts expense transactions in real time,
converts them to USD, and flags the ones that exceed the client's monthly limit.

Spring Boot 4.0.8, Java 25, PostgreSQL, Flyway.

## How the flag is decided

```
limit_exceeded = (sum of the month's transactions up to and including this one) > (the limit in effect)
```

The limit in effect is the latest one installed no later than the transaction's date. Two
consequences are worth stating outright, because both are easy to get wrong and both are
covered by tests:

- **A remaining balance of exactly zero is not exceeded.** The comparison is strict.
- **A new limit does not reset the month.** On 10.01 with 1100 USD already spent and a fresh
  limit of 2000 USD, the remaining balance is 900.

## Month timezone

**UTC**, configured as `app.month-timezone` in `application.yml`.

Transactions arrive with assorted offsets, so month boundaries have to be computed in one
explicit timezone. `domain/MonthBoundary` is the only place that conversion happens, and it is
the only thing in the codebase allowed to decide which month a transaction belongs to.

The month is also stored on the row as `occurred_month`, so reporting does not have to
recompute the boundary for every query.

## Currencies

The currency code is a **verified reference, not a string**.
`expense_transaction.currency_id` and `expense_limit.currency_id` are foreign keys to a
`currency` lookup table, and `currency_rate.base_currency` references `currency(code)`.

The schema refuses a lowercase code outright, so `kzt` cannot end up as a second currency
next to `KZT` — that is the entire point of the table. The lookup is seeded in
`V1__init.sql` with the active ISO 4217 codes, and
`WeeklyCurrencyImport` adds codes that appear later. It runs weekly rather than daily: new
codes are rare and the endpoint is paid. A transaction in a code not yet in the lookup is
refused with 404 until the next sweep.

Two further things pull in different directions and are kept separate:

- **Rates are pre-fetched for a tracked subset** — by default `KZT` and `RUB`, which are the
  bases of the `KZT/USD` and `RUB/USD` pairs the specification asks to be fetched daily (п.3).
  The list lives in the `currency_pair` table, not in `application.yml`: every poll costs money,
  so what is kept warm is an operator's decision made with a database session, not a property
  that needs a release. Adding one is `insert into currency_pair (base_currency) values ('EUR')`,
  retiring one is `update currency_pair set active = false`, and stored rates stay where they are.
- **The table has no quote column, deliberately.** The quote is `app.ledger.currency-code` —
  a single value — and `currency_rate` stores rates without a quote because it is implied. A
  stored copy here would be a second source of truth that can disagree with the configuration,
  and reconciling it would take a code-level check on every sweep. Because the quote is supplied
  at request time, moving the ledger to another currency requires **no change to this table**.
- **Any currency in the lookup is accepted** in a transaction, independently of that list. The
  subset decides what is kept warm, not what is allowed. The specification asks for "тенге,
  рублях и других валютах", so a transaction currency is never validated against the pairs
  list.

Conversion is `ledger_sum = sum × close`, with `close` meaning units of the ledger currency per
one unit of the transaction currency. The multiplication happens first and the result is rounded
once to two decimals: rounding the rate before multiplying would throw away the only precision the
conversion has.

Both the currency and the scale are configuration, not constants:

```yaml
app:
  ledger:
    currency-code: USD   # limits are denominated in it, rates are quoted against it
    amount-scale: 2      # decimal places an amount is stored and reported with
```

The specification fixes USD, and nothing in the code assumes it stays that way. Moving the ledger
to EUR is a property change and nothing else: the quote is never stored, so the daily sweep starts
asking for `KZT/EUR` on its own.

### Failure strategy for the external API

The specification asks for rates to be stored locally and for ingestion never to lose data.
The lookup order in `ExchangeRateService` is:

1. **Local database, exact day.** `close` is used; when the day has no close of its own
   (weekend, holiday) `previous_close` is used instead, which is what the specification asks
   for.
2. **Local database, most recent rate not later than that day.** This step is not in the
   specification but is needed in practice: a Monday close may not be published yet when the
   transaction arrives on Tuesday, and without this such a transaction would be stuck.
3. **One external call** for the pair, with the configured timeouts and retry count. The retry
   is resilience4j's `Retry` core API configured from `app.rates.attempts` and
   `app.rates.retry-backoff`, not its `@Retry` aspect: the aspect needs the Spring starter and AOP,
   and the policy is built once in the constructor anyway.

If all three fail, the transaction is **stored with no flag**: `ledger_sum`, `limit_exceeded`
and `limit_id` stay null and `flag_calculated` is false. Nothing is lost, and the row is
picked up by a later recalculation. Writing `limit_exceeded = false` there would be a lie in
the reporting output, which is why the column is nullable and paired with the flag.

An external outage surfaces as `ExternalServiceUnavailableException` and a 503. The exception is
named after the failure rather than after rates specifically, so the next integration reuses it.

The daily refresh covers only the configured pairs. Other currencies are fetched on demand,
because each external call costs money and sweeping currencies nobody transacts in would waste
that budget.

## Running it

Requirements: a JDK and Docker. Tests need Docker for Testcontainers; they bring up their own
PostgreSQL and Redis and do not use the compose file.

The pom targets Java 25.

```bash
export JAVA_HOME=/usr/lib/jvm/java-27-openjdk
./mvnw verify                 # compile, test, SpotBugs
./scripts/ci.sh               # the same thing plus a format check
./scripts/ci.sh --fix         # reformat instead of checking
./mvnw spring-boot:run        # needs Postgres and Redis, see below
```

The application refuses to start without a `DataSource`, so outside of tests you must provide
PostgreSQL and Redis:

```bash
cp .env.example .env          # local credentials, .env is gitignored
docker compose up -d db redis # the dependencies only

SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/financial \
SPRING_DATASOURCE_USERNAME=financial \
SPRING_DATASOURCE_PASSWORD=financial \
SPRING_DATA_REDIS_HOST=localhost \
./mvnw spring-boot:run
```

Or bring the whole stack up at once, service included:

```bash
docker compose up --build -d
curl http://localhost:8080/actuator/health
docker compose down -v
```

### Configuration

Everything external lives under `app.*` in `application.yml`, or in the environment. No URL,
credential, quota or timezone is hardcoded.

| Property | Default | Meaning |
| --- | --- | --- |
| `app.month-timezone` | `UTC` | the one timezone month boundaries are computed in |
| `app.default-limit-sum` | `1000.00` | limit applied when a client installs none |
| `app.ledger.currency-code` | `USD` | the currency limits are denominated in and rates are quoted against |
| `app.ledger.amount-scale` | `2` | decimal places an amount is stored and reported with |
| `app.scheduled-fetch-hour` | `7` | hour of the refresh, in `app.month-timezone` |
| `app.rates.source` | `twelvedata` | identifier written into every stored rate |
| `app.rates.base-url` | empty | external provider URL; empty means no provider |
| `app.rates.api-key` | empty | provider key, from the environment |
| `app.rate-limits.*` | see file | request quotas per endpoint (`limit-for-period`, `limit-refresh-period`) |
| `app.scheduling.enabled` | `true` | the daily sweep and the weekly currency import; off in tests |

### Profiles

`application.yml` holds what is true everywhere; the rest is a profile. Activate with
`SPRING_PROFILES_ACTIVE=dev` or `SPRING_PROFILES_ACTIVE=prod`.

| | what changes |
|---|---|
| `dev` | DEBUG logging, quotas effectively lifted, and the `heapdump` actuator endpoint opened |
| `prod` | Hikari sized for the flag's row lock, no health details, no `heapdump`, and virtual threads deliberately off |

`heapdump` is a full dump of the process — credentials, tokens, customer rows — so it is opened in
`dev` and nowhere else.

### Metrics

`/actuator/prometheus` is the scrape target. Alongside the JVM and HTTP metrics:

| Metric | What it answers |
| --- | --- |
| `financial_transactions_accepted_total{outcome}` | `flagged`, `within_limit` or `uncalculated` |
| `financial_rates_resolved_total{source,provider}` | how often the service pays for a rate it already had — `source` is `identity`, `database` or `external` |
| `financial_rates_sweep` | wall clock of the daily sweep and how many currencies it refreshed |
| `financial_limits_installed_total{category}` | limits installed per category |
| `financial_rate_limit_refused_total{quota}` | which endpoint is being throttled |

Tag values are closed sets on purpose: a metric tagged with a currency code or an account number does
not aggregate and its cardinality grows without bound.

## Diagnostics

The JVM is started with `-XX:+HeapDumpOnOutOfMemoryError -XX:+ExitOnOutOfMemoryError
-XX:HeapDumpPath=/app/dumps`, so an exhausted heap is written out and the process exits for the
orchestrator to restart rather than limping on. There is **no** flag that dumps the heap on a JVM
crash — HotSpot has no such facility; a native crash is diagnosed from the `hs_err` log that
`-XX:ErrorFile` points at the same directory.

A dump is as large as the heap and contains credentials, so `/app/dumps` wants a volume and its
contents are sensitive. `JAVA_TOOL_OPTIONS` is where these flags live, which is how a deployment
overrides them without rebuilding the image.

## API

Ingestion, for the bank's systems:

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/api/v1/transactions` | accept one expense operation |

```json
{
  "accountFrom": "0000000123",
  "accountTo": "9999999999",
  "currencyShortname": "KZT",
  "sum": 10000.45,
  "expenseCategory": "product",
  "datetime": "2022-01-30T00:00:00+06:00"
}
```

Client-facing:

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/api/v1/limits` | install a new limit |
| `GET` | `/api/v1/limits?account_from=` | list limits with the amount already spent |
| `GET` | `/api/v1/limits/exceeded?account_from=&month=` | transactions that exceeded a limit |
| `GET` | `/api/v1/currencies` | the currency codes the service accepts |

`POST /api/v1/limits` takes `accountFrom`, `expenseCategory` and `sum`. There is no date
field: the installation time is always "now", which is what makes backdating impossible.

`GET /api/v1/limits/exceeded` returns each transaction with `limitSum`, `limitDatetime` and
`limitCurrencyShortname`. Those are the limit the transaction's flag was actually computed
against, not whatever limit happens to be current now — which is why the limit is stored on
the transaction row.

Errors are `application/problem+json` (RFC 9457), assembled in one place,
`exception/GlobalExceptionHandler`.

Interactive documentation is served at `/swagger-ui.html`, with the JSON document at `/v3/api-docs`
and a YAML one at `/v3/api-docs.yaml`. Every payload field carries a description and an example; the
title, version and API description come from `OpenApiConfiguration`. Every handler carries `@Operation` with a
description of the part that is not obvious from its name — which limit a row reports, why a flag may be
absent, which date the service chose.

### Rate limits

Ingestion and limit installation are limited per calling address. Past the quota the answer is `429`
with a `ProblemDetail` and a `Retry-After` header. The numbers live under `app.rate-limits` in
`application.yml` as permits per period; the `@RateLimited` annotation only names the block.

The limiter is resilience4j's `RateLimiter`, driven programmatically rather than through
`@RateLimiter`: that annotation is an AOP aspect that builds **one limiter per method**, which is a
per-method quota and not the per-caller one needed here. The interceptor creates and caches a limiter
per address and uses `acquirePermission()` with a zero timeout, so a refusal happens immediately —
a client told to wait needs an answer with a `Retry-After`, not a held thread.

The quota is per address rather than per account, which is worth being explicit about: the account
travels in the JSON body, and a rate limiter runs before the body has been read. It would need either a
filter that buffers the body first or an account header, and neither is there yet.

Limiters are kept in memory, so running several instances multiplies the effective quota. That is the
right shape for shedding load per node and the wrong one for a global cap.

## Tests

The behaviour of the service is specified in Gherkin, under `src/test/resources/features`. The
feature files are the tests: each scenario is executed by the step definitions beside it, on the
JUnit Platform, so `./mvnw verify` runs them with no extra plugin.

```bash
./mvnw test                                        # everything
./mvnw test -Dtest=LimitFlagRuleFeatureTest        # the flag rule, no database needed
./mvnw test -Dtest=ApiFeatureTest                  # case 1 over real HTTP
```

| Suite | Feature | What it pins down |
| --- | --- | --- |
| `LimitFlagRuleFeatureTest` | `rule/limit_flag_rule` | the rule against every row of the specification's truth tables, plus boundaries |
| `LimitFlagRuleFeatureTest` | `rule/month_boundary` | which month a timestamp belongs to, including the offsets whose local date disagrees with UTC |
| `LimitFlagRuleFeatureTest` | `rule/ledger_policy` | what the ledger configuration refuses to bind |
| `ServiceFeatureTest` | `service/limit_flag_scenario` | case 1 against PostgreSQL, through the services |
| `ServiceFeatureTest` | `service/currency_contract` | a currency is a verified reference, not a string |
| `ServiceFeatureTest` | `service/conversion_boundaries` | rounding at half a cent, the rate lookup chain, and a provider that is down |
| `ApiFeatureTest` | `api/client_api` | case 1 over HTTP on a random port, as the specification requires |
| `ApiFeatureTest` | `api/request_validation` | every field of the payload, by boundary and by equivalence class |
| `QuotaFeatureTest` | `quota/rate_limit` | the quota is really enforced, and the refusal carries `Retry-After` |
| `ArchitectureTest` | — | the layering rules, each verified against a deliberately broken fixture |

The concurrency and backdated scenarios live in `service/limit_flag_scenario`: two concurrent
posts never see the same remaining balance, and a transaction predating any limit is measured
against the standing fallback.

`ArchitectureTest` stays a JUnit test rather than a feature, because the layering rules are
statements about code structure rather than about behaviour, and a Gherkin scenario would only
restate the Java.

The concurrency scenario is a real check, not a formality: removing the row lock makes it report
one flagged transaction instead of two. `ArchitectureTest` is the same idea for the conventions —
each rule is fed a broken fixture so a rule that stops matching anything fails instead of
silently passing.

### Boundary values and equivalence classes

Four features are organised as partition tables, because the two techniques catch different
mistakes:

- **Equivalence partitioning** takes one representative per class of input. A wrong rule usually
  breaks a whole class at once.
- **Boundary value analysis** adds the edges — minimum, minimum−1, maximum, maximum+1, and the
  exact edge. Off-by-one bugs live only there, and equivalence partitioning cannot see them,
  because both sides of the boundary are represented and both happen to work.

`api/request_validation` covers every field of the payload: the sum against
`@DecimalMin(inclusive = false)` and `@Digits(integer = 12, fraction = 2)`, a ten-digit account, a
three-letter currency, and the `occurred_at` check. `service/conversion_boundaries` covers
rounding at half a cent and the rate lookup chain. `rule/month_boundary` covers the last and first
instants of a month and the two offsets whose local date and UTC date disagree. `rule/ledger_policy`
covers what the configuration refuses to bind.

Two things are worth knowing about how these are written. Bean Validation fires only at the HTTP
boundary, so a scenario that calls the service directly proves nothing about it — which is why
the payload boundaries are an integration feature. And a 201 only proves the request was accepted,
so the valid rows also assert the row that was stored.

**Writing a feature.** Cucumber 8 with Gherkin 42 has traps that fail at compile time or report a
step as undefined without saying much: the English `@Given`/`@When`/`@Then` live in
`io.cucumber.java.en` rather than `io.cucumber.java`, there is no `{boolean}` parameter type,
quoted cells in an `Examples` table reach the step with their quotes intact, `(s)` is not an
optional group, and an empty value cannot be written as a table cell at all. `AGENTS.md` has the
details.

## AI in the project

**What was used.** OpenCode, with the context of this repository loaded: `AGENTS.md` holds the
conventions, the verified behaviour of the flag rule and the toolchain quirks discovered along
the way.

**Which MCP server and why.** None is connected yet. The obvious candidate is a PostgreSQL MCP
server in read-only mode, which would let an agent inspect the real schema and data without
guessing at column names — worth adding once the data model stops changing.

**Where AI got it wrong, and how that was noticed.** Worth recording, because the failures are
instructive:

- It stated that a zero remaining balance counts as exceeded. Running the rule against the
  specification's own table disproved it: 2000 against a limit of 2000 is not `>`.
- It computed `999.99 + 0.01` as exceeding a 1000 limit. The truth-table feature caught it; the
  sum is exactly 1000.
- It added a "limit already exists" error on install. That contradicts the specification, where
  installing a second limit for the same category is how the client raises it in case 1. The
  scenario exposed it by failing to raise the limit from 1000 to 2000.
- It implemented the default-limit creation as a read-then-write, which lets two concurrent
  first transactions each create a limit. The concurrent-posts scenario exposed it.
- Several smaller ones: a `wget` health check against an image with no `wget`, Jackson 2 imports
  on a Jackson 3 stack, and the pre-Spring-Framework-7 exception-handler signature.
- Once more, in the tests themselves: it wrote a feature whose steps did not accumulate the
  month's running total, so the third transaction of a day was judged against the opening
  balance. The failure surfaced as a scenario reporting the wrong flag, which is what the Gherkin
  layer is for.

In each case the fix was to the generated code, never to the test that caught it. The scenarios
were written from the specification's own numbers, so they had no reason to agree with a wrong
implementation.

**What was written by hand.** The flag rule and its truth table, the SQL migration, and the
decision to store the limit on the transaction row. Those encode the specification rather than
a pattern, so they were reasoned about rather than generated.

**How to apply the skill.** `.agents/skills/` is not populated yet. When it is, the intended
entry points are `domain/LimitFlagRule` for changing the rule, and
`features/service/limit_flag_scenario.feature` for changing anything that touches it — the
scenario reads as an English description of the behaviour, so it is the cheapest place to look
before touching the flag logic.

## Accounts

`account_from` is a `varchar(10)` with a digit check, and there is deliberately **no account
table**. The specification says one thing about accounts — "строка из 10 цифр" — which describes
a field format, not an entity. Normalising accounts would need facts about them that nobody has
specified: who owns the account, its currency, whether it is closed. Adding a table for a string
that is already unique by definition would be structure without a question behind it.
- No `.agents/skills/` directory, no MCP configuration, and no commits yet.
- `application.yml` has no datasource section by design; see the run instructions.
- Rates are not cached in Redis. The specification lists that as optional, and it would be
  worth it only if the database lookup showed up as a bottleneck.
- `exchange_rate` has no index on `(base_currency, rate_date)` beyond its unique constraint; the
  lookup is already served by it, but a wider index may earn its place under real volume.