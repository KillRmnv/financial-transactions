# Financial Transactions

Microservice for monthly USD spend limits. It accepts expense transactions in real time, converts
them to the ledger currency, and flags the ones that go over the client's monthly limit.

Spring Boot 4.0.8 · Java 25 · PostgreSQL · Flyway · Redis · Resilience4j · ShedLock · MapStruct ·
WireMock · Cucumber

## What it does

Three capabilities, and the third is the one that is easy to get subtly wrong:

- **Ingestion.** `POST /api/v1/transactions`, or `/batch` for up to 100 at once.
- **Limits.** Install a new one, list them with what is already spent, list the transactions that
  went over. An existing limit cannot be updated — only added.
- **The flag.** A transaction is flagged when the running total of its month, including itself,
  exceeds the limit in effect **on that transaction's date**. A new limit does not retroactively
  change the flag on earlier transactions, and a remaining balance of exactly zero is not exceeded.

Everything a caller sends is verified against a currency lookup rather than stored as a string, so
`kzt` cannot become a second currency next to `KZT`.

## Running it

Needs a JDK and Docker. The application refuses to start without a `DataSource`, so outside of tests
you must provide PostgreSQL and Redis:

```bash
cp .env.example .env          # local credentials, .env is gitignored
docker compose up -d db redis # the dependencies only
./mvnw spring-boot:run
```

Or the whole stack at once, service included:

```bash
docker compose up --build -d
curl http://localhost:8080/actuator/health
docker compose down -v
```

Swagger is served at `/swagger-ui.html`, the JSON contract at `/v3/api-docs` — both in the `dev`
profile, off elsewhere.

Full instructions, configuration, JVM flags, sizing and the three things that look like bugs when
you try it against a live provider: **[`docs/operations.md`](docs/operations.md)**.

## Building and testing

```bash
export JAVA_HOME=/usr/lib/jvm/java-27-openjdk
./mvnw verify            # compile, test, SpotBugs
./scripts/ci.sh          # the same plus a format check
./scripts/ci.sh --fix    # reformat instead of checking
```

Tests need Docker for Testcontainers; they bring up their own PostgreSQL and Redis and do not use
the compose file. The behaviour is specified in Gherkin under `src/test/resources/features`, and the
feature files are the tests.

## Documentation

The README describes the project. Everything else is in `docs/`, in Russian:

| File | What is in it |
| --- | --- |
| [`docs/assumptions.md`](docs/assumptions.md) | Every judgement call, and what was verified against a live rate provider |
| [`docs/domain-rules.md`](docs/domain-rules.md) | The flag rule, the month timezone, accounts |
| [`docs/currencies.md`](docs/currencies.md) | Currency lookup, the lookup chain, failure strategy, the Redis cache |
| [`docs/database.md`](docs/database.md) | Schema, migrations, the report query, transactions and locks |
| [`docs/api.md`](docs/api.md) | Endpoints, error format, rate limits, how the contract is published |
| [`docs/operations.md`](docs/operations.md) | Running it, configuration, JVM flags, memory, profiles, metrics, Docker |
| [`docs/testing.md`](docs/testing.md) | What is covered and how honestly, concurrency, boundary technique |
| [`docs/test-cases.md`](docs/test-cases.md) | Index of every test case with its design technique |
| [`docs/ai.md`](docs/ai.md) | Which AI tools and MCP servers were used, and where the AI got it wrong |
| [`docs/estimate.md`](docs/estimate.md) | Time estimate, and how the work compared to the specification |
| [`docs/known-gaps.md`](docs/known-gaps.md) | What is not done, and why |

The database model is [`docs/db-model.puml`](docs/db-model.puml), rendered to
[`db-model.png`](docs/db-model.png) with PlantUML.

`AGENTS.md` holds the conventions an agent needs before touching anything: layering, naming, the
rules that are easy to break, and the toolchain quirks of Boot 4 on this machine.

## Repository layout

```
src/main/java/com/idfinance/financial_transactions/
  controller/    HTTP layer, no business logic
  service/       business logic, owns the transactions
  repository/    Spring Data JPA
  domain/        pure logic: the flag rule, the month boundary, an exchange rate
  entity/        JPA entities
  dto/           request and response records
  mapper/        MapStruct, no hand-written converters
  exception/     one handler, every error a ProblemDetail
  client/        HTTP adapters to external providers
  cache/         the Redis rate cache
  config/        properties and configuration
  web/           the rate limiter interceptor
src/main/resources/db/migration/   Flyway, schema first
src/test/resources/features/       Gherkin specifications, which are the tests
```