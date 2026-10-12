# AGENTS.md

Spring Boot 4.0.8 / Java 25 Maven microservice for monthly USD spend limits and currency conversion.

## Source of truth

`Тестовое_задание_Junior_Java_разработчика_2026.md` (Russian) is the specification. Read it before implementing anything. It defines:
- the input/output JSON contract (`account_from`, `account_to`, `currency_shortname`, `sum`, `expense_category`, `datetime`; responses add `limit_sum`, `limit_datetime`, `limit_currency_shortname`),
- the `limit_exceeded` truth tables ("1 случай" / "2 случай") that define correct behaviour,
- requirements 1–11 and the optional `*` extras.

Where this file and that document disagree, the document wins.

`README.md` describes the project; everything else is in `docs/`, in Russian. This file is the
third thing and overlaps with neither: it is what an agent needs *before* touching anything.

## Toolchain: JDK and the Lombok pin

The pom sets `<java.version>25</java.version>`, but only two JDKs are installed here: **21** (`/usr/lib/jvm/java-21-openjdk`, which is also `JAVA_HOME=/usr/lib/jvm/default`) and **27** (`/usr/lib/jvm/java-27-openjdk`). `javac` 21 rejects `--release 25` outright, so **JDK 27 is the only usable JDK locally**; CI installs JDK 25 because that is what the pom targets. A CI-only failure that looks like a compilation or annotation-processing bug is usually that difference.

The pom pins `<lombok.version>1.18.48</lombok.version>`. Do not remove it: the version the Boot 4.0.8 parent provides (1.18.46) crashes on JDK 27 with `ExceptionInInitializerError: com.sun.tools.javac.tree.EndPosTable`, which fails the build in `compiler:default-compile` with no useful context.

```bash
export JAVA_HOME=/usr/lib/jvm/java-27-openjdk
./scripts/ci.sh                    # format check + tests + SpotBugs  (the local CI equivalent)
./scripts/ci.sh --fix              # reformat instead of checking
./scripts/ci.sh -Dtest=Foo         # pass Maven args through
./mvnw test                        # tests only (needs Docker)
./mvnw test -Dtest=ClassName       # single class
./mvnw test -Dtest=ClassName#method# single method
./mvnw spotbugs:check              # static analysis standalone
```

Do not use bare `mvn`: system Maven 3.9.16 is installed and works, but it inherits `JAVA_HOME` (Java 21) and fails on `--release 25`. `scripts/ci.sh` pins `JAVA_HOME` itself when the current one has no `javac`. The wrapper is cached (`~/.m2/wrapper/dists/apache-maven-3.9.16-bin`, `distributionType=only-script`), so no download happens. Maven Central is reachable; no private repo and no `settings.xml`.

## Running the app

The app refuses to start without a `DataSource`, and `application.yml` has no datasource section by design. Anything outside tests must supply `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` and `SPRING_DATA_REDIS_HOST`.

```bash
cp .env.example .env                # .env is gitignored; compose reads it
docker compose up -d db redis       # dependencies only, no image build
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/financial \
SPRING_DATASOURCE_USERNAME=financial SPRING_DATASOURCE_PASSWORD=financial \
SPRING_DATA_REDIS_HOST=localhost ./mvnw spring-boot:run

docker compose up --build -d        # service + db + redis in one command
```

Tests do **not** use compose; Testcontainers brings its own containers.

## Mapping: MapStruct is mandatory

Hand-written DTO↔Entity converters are not allowed. `TransactionMapper` is the pattern to copy.

- If a mapping cannot be expressed declaratively, use `default` methods — **not** a `toDto()` method on a service or a static utility.
- **Never build a response DTO by hand in a controller.** That is the failure this rule exists to prevent, and it is easy to commit by accident: a twelve-argument `new TransactionWithLimitResponse(...)` with a ternary per optional field is unreadable, and no change to it breaks at compile time. Put the awkward parts in a named `default` method on the mapper.
- **Name every `source`, including the ones that already match.** An implicit mapping silently stops working the day a field is renamed, and the compiler says nothing. Only `ignore = true` needs no source.
- `unmappedTargetPolicy = ReportingPolicy.ERROR`: adding a field to an entity breaks the build until the mapper names it. That is the point.
- Mappers live in a `mapper` subpackage beside the layer they serve.
- **Pass server-decided values as parameters, not as `@Mapping(expression = ...)`.** An inline expression makes MapStruct emit a bare dereference that SpotBugs then flags in generated code, and the fix belongs in the call signature rather than in a suppression.
- **Build gotcha:** MapStruct must be in `annotationProcessorPaths` for *both* `default-compile` and `default-testCompile`. The pom registers three processors — Lombok, `lombok-mapstruct-binding` (0.2.0) and `mapstruct-processor`. Dropping one makes generation fail silently or yield empty mappers.

## Naming: full names for injected collaborators

A field injected through the constructor is named after its type, unabbreviated: `limitService`, `expenseLimitRepository`, `currencyRateRepository`, `ratesClient`. Never `limits`, `rates`, `clients`, `categories`.

The rule is about the reader, not about taste: `this.limits.install(...)` says "some collection of limits", while `this.limitService.install(...)` says which collaborator and which layer owns it. It also stops the web layer from reading like a data layer. Applies to tests too.

Where a record already carries the same name as the type, use a different local instead of shadowing (`var row`, `var stored`).

## Lombok is expected, not optional

Use it wherever it removes boilerplate without hiding behaviour: `@Getter` on entity fields, `@NoArgsConstructor(access = PROTECTED)` for JPA, `@RequiredArgsConstructor` for constructor injection, `@Slf4j` instead of `LoggerFactory.getLogger(...)` in every class.

Records stay records — Lombok is for what records cannot express. Do not add `@Data` to an entity: it generates a `toString` that walks a lazy proxy, and an `equals`/`hashCode` that JPA does not want.

## Exception handling: one handler, ProblemDetail everywhere

- Exactly one `@RestControllerAdvice`, living in the `exception` package and extending `ResponseEntityExceptionHandler` (verified present in spring-webmvc 7.0.9, which Boot 4.0.8 pulls). Per-controller `@ExceptionHandler` methods are forbidden, and `@ResponseStatus` is not used alongside the handler.
- **Name an exception after the failure, not after the dependency.** `ExternalServiceUnavailableException` carries a `service` name so the next integration reuses it; `RatesUnavailableException` pinned the name to rates and would have needed renaming the moment a second provider arrived. Reserve a distinct type for a genuinely distinct condition, such as `RateNotFoundException`.
- **Never narrow a wire format to make it uniform.** `@JsonFormat(pattern = …)` on a DTO field looks like tidiness and is a contract change: the pattern `yyyy-MM-dd'T'HH:mm:ssXXX` rejects `2022-01-02T00:00Z`, which is perfectly good ISO 8601 and which the scenarios send. Document the accepted format with `@Schema(description = …, example = …)` instead and let Jackson keep its standard leniency.
- **Never write a response body from an interceptor or a filter.** They have no message converters, and serialising a `ProblemDetail` there produces a body that differs from the handler's. Throw a domain exception and let `GlobalExceptionHandler` render it — the rate limiter does exactly this with `RateLimitExceededException`, which is also why it extends `DomainException`.
- Every error leaves the API as `ProblemDetail` (RFC 9457): `type` URN, `title`, `status`, `detail`, `instance`. Bean Validation failures add the field-level violations.
- Domain exceptions live in the `exception` package. The **handler** decides the HTTP status — services must not import `HttpStatus`, construct a `ProblemDetail`, or return `ResponseEntity`.
- No `try/catch` around external HTTP calls in services. The rates-client adapter turns transport failures into a domain exception; the retry/timeout strategy lives in `TwelveDataRatesProvider` and is described in `docs/currencies.md`.
- **Never hardcode the ledger currency.** `USD` appears in the specification, not in the code: `config/LedgerPolicy` carries it (`app.ledger.currency-code`) together with `amountScale` (`app.ledger.amount-scale`), and `LedgerPolicy.round(...)` is the one place an amount is rounded. Reintroducing a `LEDGER_CURRENCY` constant — in a service, or worse in `domain/ExchangeRate` — undoes the whole point. The same goes for `USD` in test stubs: `BddInfrastructureConfiguration` reads `LedgerPolicy` for exactly that reason.
- **`LedgerPolicy` validates its own binding.** The code is checked against `^[A-Z]{3}$` in the compact constructor, so a typo in a property fails at startup instead of surfacing later as an empty currency lookup.

## Static analysis is not optional

- **SpotBugs** runs with `effort=Max`, `threshold=Medium`, bound to the `verify` phase. When it fires on concurrency, locking or null handling — the areas the spec grades on — fix the code. Do not add `@SuppressFBWarnings` or loosen the threshold.
- **Spotless** enforces the Spring Java Format, on `src/**/*.java` plus `src/**/*.yml` and `*.properties`. Reformat with `./scripts/ci.sh --fix` or `./mvnw spotless:apply`, never by disabling the check. The Eclipse config is loaded from the classpath (`io/spring/javaformat/formatter/eclipse/formatter.prefs`), which is why `spring-javaformat-formatter` is a **dependency of the plugin**, not of the project. Changing that placement breaks the formatter with a confusing "Unable to locate file with path" error.
- **ArchUnit** (`ArchitectureTest`) enforces the conventions below in milliseconds, so it runs on every commit. Beyond the layering rules it also asserts: `@Mapper` types are interfaces, `@RestControllerAdvice` lives in `..exception..`, `@Component` beans have no mutable instance fields, and — see the next point — that beans are `final`.
- **Two ArchUnit rules bracket `final`, and they are opposites.** `transactionalBeansMustRemainProxyable` forbids it where something proxies the bean; `beansMustBeFinalUnlessSomethingProxiesThem` requires it everywhere else, exempting `@Transactional`, `@Cacheable` and `@Async` and classes named `*Impl` (MapStruct generates those, and their finality is not ours). **Nine classes are therefore intentionally not final**: `CurrencyImporter`, `CurrencyPairService`, `ExchangeRateService`, `LimitService`, `ReportingService`, `ScheduledJobService`, `ScheduledRatesFetcher`, `TransactionService`, `WeeklyCurrencyImport`. The list grows by one every time something acquires a `@Transactional` method, so read it from the code rather than trusting it: `grep -L 'final' ` over the beans in `service` and `client`, or let `ArchitectureTest` tell you, which is what it is for. CGLIB cannot subclass a final class — verified, `IllegalArgumentException: Cannot subclass final class` — so marking one final does not fail the build, it silently removes the transactions. Note ArchUnit 1.5.1 has `beFinal()` only on *fields*: for classes it is `haveModifier(JavaModifier.FINAL)`, and `should(notHaveModifier(FINAL))` reads as the opposite of what it does.
- **ArchUnit 1.5.1 has no `haveMethodsAnnotatedWith`.** Proxied beans are recognised by an annotation on a *method*, so `ArchitectureTest` carries its own `DescribedPredicate` over `getAllMethods()`. Add a new proxy-requiring annotation to `needsAProxy()`, or the rule will start rejecting beans that legitimately need one.
- **The rules do not check themselves, and that is accepted.** There are no deliberately broken fixtures: every one of them caused more trouble than it caught, and they cost a whole package that had to be kept outside the component-scanned tree. ArchUnit's own default is strict — an empty `should` fails — and `src/test/resources/archunit.properties` relaxes it to `failOnEmptyShould=false`, which was needed while the layers did not exist and no longer is. **Deleting that file would make a typo'd rule fail instead of passing silently**, which is the more valuable of the two protections; the file survives only because nobody has decided to remove it.
- Read a new rule twice before trusting it. A condition inverted in the wrong direction still passes on healthy code, because the wrong direction is usually the one nothing violates.

## Boot 4 dependency naming

This is not Boot 3. `spring-boot-starter-web`, `-test`, `-data-jpa-test` etc. no longer exist under the old names — the pom uses `spring-boot-starter-webmvc`, `-webmvc-test`, `-data-jpa-test`, `-data-redis-test`, `-flyway-test`, `-validation-test`, `-actuator-test`, and there is **no** `spring-boot-starter-test` aggregate. Copy the existing coordinates when adding dependencies. Mockito and AssertJ come in transitively with the `*-test` starters.

**MapStruct, WireMock, Spotless, SpotBugs, ArchUnit, resilience4j, Cucumber and springdoc are not managed by the Boot parent**, so their versions live in `<properties>` with a comment — except springdoc, which pins `3.1.0` inline. If a version is missing, the build breaks on the next Boot upgrade; keep the pin. Note that `spotbugs-maven-plugin` and `spotbugs-annotations` use different version schemes (`4.10.4.1` vs `4.10.4`), which is why they have separate properties.

`cucumber.version` is pinned because **Cucumber 8.0.4 is built against Spring 7.0.9**, which is exactly what the Boot 4.0.8 parent pulls, and it resolves JUnit Platform 6.0.3 — the version Boot 4 manages. Do not drop it to a 7.x line: `cucumber-spring` 7.x targets an older Spring and would break the `@CucumberContextConfiguration` wiring.

**`RateCachePolicy` validates its own binding**, on the same reasoning as `LedgerPolicy`: a TTL of zero fails at startup rather than becoming a cache that expires instantly, and a blank key prefix fails rather than writing this service's keys into the root of a Redis that may be shared. In a compact record constructor the fields are not assigned yet, so the checks read the **parameters**.

**There are two rates stubs and each suite picks one on purpose.** `StubRatesConfiguration` publishes an in-process `StubRatesProvider` and the service and quota suites import it; `WireMockRatesConfiguration` starts a server and points the *real* `TwelveDataRatesProvider` at it through `app.rates.providers.twelvedata.base-url`, which `ApiFeatureContext` sets — **not** on the imported configuration, because `@TestPropertySource` there is not applied. An in-process bean replaces the whole client, so the request the adapter builds — the path, the query parameters, the JSON it parses — is never exercised. Only the WireMock suite exercises it, and it is the only one that can say anything about the adapter. `BddInfrastructureConfiguration` deliberately holds neither: clock and `RestClient.Builder` only, so adding a stub to the wrong suite is an import you can see.

**Both stubs are `@Primary`, and `ChainedRatesProvider` is `@Primary` too** — so a suite importing both rates stubs gets a context that refuses to start, which is the intended outcome rather than an accident. It is also why `features/api/rates_fallback.feature` exists as a separate pair of stubs from `client_api`: the composite asks the paid provider first and falls through, and only a suite that wires *both* adapters can say anything about that order.

**What the WireMock stub got wrong, and why it is the interesting one.** It answered `/currency` with a currency list, because that is what the adapter asked for, and it put `previous_close` in `/time_series` answers, because the adapter read that field. Both endpoints and that field turned out not to exist on the real provider. A stub reproduces the contract the code assumes; only the live provider tests the contract the code got. The stub now answers `symbol_search` and builds a closed day as an empty `values`, which is the shape the provider actually sends.

**A `/` inside a Cucumber expression is an alternation, not a character.** `"the provider answers {string}/{string}"` fails to parse with `Alternative may not be empty` at the slash, and the failure surfaces on every scenario in the run rather than on the one that used it, because the expression is compiled per pickle. Write `against` instead.

## Testing pitfalls

- **Surefire only.** No `maven-failsafe-plugin` is configured. A class named `*IT` will never run under `./mvnw verify` — which the spec requires for the end-to-end test. Name integration tests `*Tests` or `*Test`.
- **Testcontainers is required for most tests.** `FinancialTransactionsApplicationTests` is a full `@SpringBootTest` that boots Postgres and Redis; there are no embedded-database or mock alternatives, so `./mvnw test` cannot run without a working Docker daemon.
- Every `@SpringBootTest` that needs infrastructure must `@Import(TestcontainersConfiguration.class)` to get the `@ServiceConnection` beans. Redis is a raw `GenericContainer` with `@ServiceConnection(name = "redis")`.
- **Testcontainers image tags are pinned to `postgres:16` and `redis:7`.** Do not switch them to `:latest`. Those tags are not cached on this machine and Docker Hub pulls stall with "Docker image pull has not made progress in 30s", which makes `contextLoads` hang for minutes before it can even start. If a test needs a new image, pre-pull it first and pin an exact tag.
- `TestFinancialTransactionsApplication` exists purely as a dev-time entry point that starts the containers and then runs the app; it is not a test.
- **Inject through the constructor, in tests too.** A `@SpringBootTest` uses an explicit constructor with final parameters, not `@Autowired` fields: field injection hides the dependencies from the class declaration, and a test that reads as a list of beans tells you nothing about what the scenario actually needs.
- **Use AssertJ `SoftAssertions` wherever a test makes more than one independent claim.** A hard `assertThat` stops at the first failure, so a test that checks the row count, the total and the flag count reports only the row count — and the interesting one, the flag count, is exactly the thing a missing row lock breaks. Keep hard asserts for the one claim that a failure makes the rest meaningless.
- **Inject `java.time.Clock`, never `Instant.now()` / `LocalDate.now()`.** The flag logic depends on the current time; tests must be able to "move the clock" to reproduce the truth tables. `SettableClock` in the test sources is the substitute, and it is the only way to place an event in time — a scenario that needs a limit installed on a specific date sets the clock rather than reaching into a repository. This is how the backdated-transaction bug stayed hidden: the early scenarios all moved the clock to the installation dates, so the real case (a transaction older than anything the service knew) was never exercised until the backdated scenario was added.
- The quota features override the numbers through `@TestPropertySource` on `QuotaFeatureContext` rather than exercising the production ones: a 60-token bucket refilling 10 per second cannot be exhausted deterministically, and a quota that is never exhausted is a quota that is never verified.
- **The suites seed their own currencies through `TestCurrencyLookup`, synchronously at context startup, and idempotently.** The migration seeds three codes and the rest comes from the provider, which is right for a deployment and useless for a suite: the stub publishes two, so every scenario working in anything else would be refused with 404 for a reason unrelated to what it tests. Synchronous is the point — `CurrencyBootstrap` hands its work to a virtual thread precisely so a slow provider cannot hold the startup, and exactly that property would make a scenario about the lookup race it. It uses a `TransactionTemplate` because `@PostConstruct` has no ambient transaction and Hibernate refuses a modifying query outside one.
- `features/service/currency_contract.feature` is the place for anything about the currency lookup, including "the schema refuses it".

## BDD: the Cucumber layer

`src/test/resources/features/**` holds executable specifications written in Gherkin. They run on the JUnit Platform engine, so `./mvnw verify` picks them up with no extra plugin, and `./mvnw test -Dtest=ApiFeatureTest` runs one suite.

Cucumber 8 is a **major version with two traps that cost real time**, and both fail at compile time with no useful message:

- **`@Given`/`@When`/`@Then` live in `io.cucumber.java.en`, not `io.cucumber.java`.** Gherkin 42 localised the keywords, so the English annotations moved into a per-language package. `io.cucumber.java.Given` does not exist.
- **There is no `{boolean}` parameter type.** The truth tables need one, and there is no built-in to reach for. Amounts use `{word}` or `{string}` and are converted with `new BigDecimal(...)`.

More Gherkin 42 behaviour worth knowing before writing a feature:

- **Quoted cells in an `Examples` table are not unquoted.** `| "0" |` reaches the step as `""0""`, so it matches nothing. Values that feed a `{string}` parameter must be quoted; values feeding a `{word}` parameter must not.
- **`(s)` is not an optional-group syntax.** `"{int} transaction(s) is stored"` matches only the literal text with `(s)`. Write a second annotation on the same method for the singular case, or pick one wording.
- **An empty cell matches no parameter type at all**, so `| effective |` with a blank reports an *undefined step* rather than the null the code returns. Spell the case out: `| none |`.
- **`@Given` and `@When` on one expression is a duplicate, and it fails the whole glue class.** Cucumber 8 raises `DuplicateStepDefinitionException` — on one method or on two — and the failure is not the one scenario but every scenario in the suite. Declare one keyword, and use `And` in the feature where the step is a precondition.
- **`{string}` already consumes the quotes**, so a step for `"KZT" is set to be tracked` is declared `{string} is set to be tracked`. Wrapping it as `"\"{string}\" ..."` adds quotes that are not in the Gherkin and the step never matches. A `{code}`-style alias needs a registered `ParameterType`; there is none in this project.
- **A scenario cannot observe a throw.** A `When` step that posts an invalid request fails the scenario as an error instead of passing it. Provoke the refusal inside a `Then` step with `assertThatThrownBy` — see `a transaction in {string} for {string} USD is refused as not found`.
- **Amounts are `{word}`, not `{double}`.** `999.99 + 0.01` is not 1000 in binary floating point, and the truth-table row that depends on that being exactly 1000 is precisely the row a `double` parameter silently breaks.
- **A `{word}` parameter next to another one is a trap.** `a transaction of {string} {word} happened on {string}` also matches `a transaction of "100" USD happened on "2022-01-05"`, and Cucumber reports that as *ambiguous* rather than preferring one definition. Give each shape a distinct wording.
- **An empty value cannot be expressed in a table cell.** Gherkin passes a quoted cell through with its quotes, so `| "" |` arrives at the step as `""""`. Give an empty or absent value a step of its own.

### Four runners, four contexts

| Runner | Features | Context |
|---|---|---|
| `bdd/LimitFlagRuleFeatureTest` | `features/rule` | `@ContextConfiguration`, no Boot, no database — runs in ~200 ms |
| `bdd/service/ServiceFeatureTest` | `features/service` | `@SpringBootTest` + Testcontainers |
| `bdd/api/ApiFeatureTest` | `features/api` | `RANDOM_PORT` + Testcontainers + a WireMock rates provider |
| `bdd/quota/QuotaFeatureTest` | `features/quota` | `RANDOM_PORT` + a 3-token quota via `@TestPropertySource` |

**One Cucumber run cannot carry two Spring contexts**, because `@CucumberContextConfiguration` is resolved once per run. That is why there are four runners rather than one, and why the quota suite is separate: the buckets live in a map inside the interceptor for the lifetime of the context, so sharing one context would let the API scenarios spend the quota the quota scenarios are trying to exhaust.

- **Glue is pinned per runner** (`GLUE_PROPERTY_NAME` = the one subpackage it needs). Leaving it at the parent package makes every runner instantiate every step class, so a step needing beans the context lacks fails the suite on wiring rather than on behaviour.
- **`@CucumberContextConfiguration` must not sit on a `@Component` or `@Configuration` class** — Cucumber registers glue classes itself and rejects a duplicate bean definition. Use `@ContextConfiguration`, `@SpringBootTest` or a plain class.
- **Object factories are found by SPI, so `cucumber-spring` claims every run.** The pure rule suite therefore still gets an (empty) Spring context; that costs milliseconds, where a `@SpringBootTest` would start containers.
- `@LocalServerPort` is unusable in a step class: it injects into a JUnit test instance, and a glue class is a Spring bean. Read `local.server.port` from the `Environment` instead.
- Steps are package-private and constructor-injected like any other bean. Do not annotate them `@Component` — that is the duplicate-bean-definition case above.
- **Glue may list several packages** (`bdd.api,bdd.store`). `bdd.store` holds the steps that read stored rows and are needed by both the service and the API suites; defining them twice would make the two copies drift.

## Test technique: boundaries and equivalence classes

The behaviour is specified in Gherkin, and the partition tables live in `Examples` blocks. Two techniques, used for different jobs:

- **Equivalence partitioning** picks one representative per class of input. A wrong rule usually breaks a whole class at once, so one scenario per class is enough.
- **Boundary value analysis** adds the edges: minimum, minimum−1, maximum, maximum+1, and the exact edge itself. Off-by-one bugs live only there, and equivalence partitioning cannot see them because both sides of the boundary are represented and both happen to work.

Where each lives:

| Feature | Technique applied |
|---|---|
| `api/request_validation` | EP and BVA on every field of the payload: `sum` (`@DecimalMin` inclusive=false, `@Digits(12,2)`), a ten-digit account, a three-letter currency, and the `occurred_at` CHECK |
| `service/conversion_boundaries` | BVA on HALF_UP rounding at half a cent, the precision of multiply-then-round, the classes of a non-positive rate, and the rate lookup chain |
| `rule/month_boundary` | BVA on the last and first instants of a month, plus the two offsets whose local date and UTC date disagree |
| `rule/ledger_policy` | BVA on the shape of a currency code and on a scale that cannot be negative |

**Bean Validation can only be tested through HTTP.** The annotations fire at the web boundary, so a scenario that calls the service directly proves nothing about them — that is why `request_validation` is an integration feature.

**A valid boundary value must be checked in the database too.** A 201 only proves the request was accepted; `1 transactions are stored` and the amount on the row are what prove the value survived.

### Three behaviours the boundary tests pinned down

- **`DataIntegrityViolationException` is answered with 400** (`GlobalExceptionHandler`). These are CHECK, UNIQUE and FK rules that Bean Validation cannot express, and they are all caused by input reaching further than the annotations allow. Without the handler a `datetime` before 1990 came back as a 500, which reads like a server fault rather than a bad request.
- **A body Jackson cannot read is answered with our own problem type.** Spring's default handling returns a `ProblemDetail` with no `type`, so it carried `about:blank` and missed every other field the handler fills in. `handleHttpMessageNotReadable` is overridden to keep one shape for one API.
- **The rate is looked up for the day of the expense** (`MonthBoundary.dayOf`), not for the first day of the month. A price from the 3rd is not the price of the 5th, and a transaction on a Sunday asks for Sunday. The *month* for the flag is a separate question and still uses `monthOf`, because a limit is monthly while a rate is a quote for a moment. Both answers are stored on the row (`occurred_month`, and the rate), so reporting never recomputes either.

## Target architecture

- Package root is `com.idfinance.financial_transactions` (underscore, not hyphen; the hyphenated artifact name is invalid Java).
- Subpackages by layer: `controller` / `service` / `repository` / `entity` / `dto` / `mapper` / `exception` / `config`, plus `client` for the external provider adapters, `domain` for the pure logic, `web` for the rate limiter, and `cache` for the Redis rate cache. A package with one class in it is fine; a package added for symmetry is not.
- Layering: Controller → Service → Repository. Controllers do no business logic, and services touch no `org.springframework.web..` or `jakarta.servlet..` type.
- Two REST API groups: an ingestion API for transactions (bean-validated), and a client-facing API (set a new limit, list limits, list transactions that exceeded the limit, list currencies).
- All errors return Spring `ProblemDetail` (RFC 9457).
- Currency rates come from an external provider behind `RatesProvider` (`service` package, so the contract sits with the code that depends on it). The HTTP adapters live in `client`, one class per provider, and the service layer does not know they exist. Providers live in `app.rates.providers.<key>`, each with its own `base-url`, `api-key` (optional), `source` and `order`; `ChainedRatesProvider` asks them in `order` and nothing else needs editing when one is added. `NoRatesProvider` is the fallback when none is configured, which is the default.
- **`ChainedRatesProvider` is `@Primary` and composes the paid and free providers** rather than the service choosing between them: it asks the paid one first, because each call to it costs money, and falls through to the free one — on an empty answer *and* on a failure, since the free one has neither a key nor a quota to spend and is exactly what is wanted when the paid provider is out. Both fall-throughs carry a `SafeLog` warning, because the adapter's exception message contains the provider's API key. It takes both as `ObjectProvider`, so a context with neither still starts. Two `@Primary` beans of the same interface is a context that refuses to start, which is why exactly one composite carries it.
- **`RatesProvider.name()` is configuration, not a constant.** The value lands in `currency_rate.source`, so a staging instance on a sandbox and production on a paid tier stay distinguishable in stored data; a hardcoded `"external"` would make every provider indistinguishable. Stubs in tests return their own name (`"stub"`).
- Schema-first migrations: Flyway scripts in `src/main/resources/db/migration` — `V1__init.sql`, `V2__shedlock.sql`, `V3__scheduled_job.sql`, `V4__amount_scale.sql`, `V5__limit_weight.sql`, `V6__shedlock_lock_until.sql`. Schema changes go in a new versioned migration, never by hand-editing an applied one. That rule is not pedantry: `V1` carried all of ISO 4217 as data, and it is precisely because the file cannot be edited that those rows had to be removed by *not re-running* the migration rather than by fixing them in place. It is also load-bearing against Flyway itself, which validates the checksum of every applied migration and refuses to start on a mismatch — so editing `V2` to correct a column name would have taken the deployment down rather than fixed it.
- **A CHECK constraint cannot contain a subquery in PostgreSQL.** When a rule needs to consult another table, use a foreign key; do not reach for a trigger to work around it.
- **A repository method is declared only when something calls it.** `JpaRepository` already supplies `findAll`, `findById`, `save` and `count`; anything else must have a caller today. Speculative query methods are untested code that rots.
- **A report is one statement, not a loop.** `ExpenseLimitRepository.reportForAccount` is the specification's JOIN-with-a-subquery, an-aggregate-and-a-grouping in one native query: the limits, joined to `expense_category` and `currency` for their codes, left-joined to a grouped subquery over the month's transactions. The previous shape asked for the month total and the category once per limit, which is an N+1 whose length grows every time a client raises a limit — precisely the account that reads this report most often. **Keep it that way:** a second per-row lookup here is the same defect in a different place, and `features/api/client_api.feature` asserts the numbers it produces.
- Configuration only through properties and environment variables. No hardcoded URLs, credentials, quotas, timezone constants or currency codes. **Compose substitutes only into the variables named under `environment:`** — a name added to `.env` and not listed there is silently not passed. That is how `RATES_API_KEY` went missing once: the key was in `.env` and the application never saw it.

## Domain rules that are easy to get wrong

- A limit applies only from its installation datetime forward; a new limit does not retroactively change the flag on earlier transactions.
- Monthly boundaries are computed in **one** explicitly chosen timezone (`app.month-timezone`, UTC), documented in `docs/domain-rules.md`. Transactions arrive with assorted offsets.
- Default limit is **1000 USD** per category when none was set. Categories are exactly `product` and `service`, seeded in the migration.
- Existing limits cannot be updated — only new ones can be added, and the new limit's datetime is always "now" (`POST /api/v1/limits` has no date field, which is what makes backdating impossible).
- **The flag rule, verified against all 8 rows of the truth table:** `limit_exceeded = (sum of the month's transactions running total, including this one) > (the limit in effect on the transaction date)`. "In effect" means the latest limit with `created_at <= occurred_at`. In code: `runningTotal.compareTo(limit.getSum()) > 0`.
- **An exactly-zero remaining balance is NOT exceeded.** On 12.01 the running total is 2000 against a limit of 2000, and `2000 > 2000` is false. (An earlier version of this file claimed the opposite; that was wrong.)
- A new limit does **not** reset what was already spent: on 10.01 the remaining balance is 900 = 2000 − 1100, so transactions predating the new limit still count toward the month's total.
- The `Остаток` column of the second case in the spec is arithmetically wrong (it says −200 where 400 − 900 = −500). The flag column is consistent with the rule above. Trust the rule, not that column.
- Concurrent transaction posts for the same client must not both observe the same remaining balance. The concurrent-posts scenario in `features/service/limit_flag_scenario.feature` verifies this, and it is a real check rather than a formality: with the row lock removed it reports 1 flagged transaction instead of 2, because two threads read the same running total. The lock is `ExpenseLimitRepository.lockEffectiveOn` (a `@Lock(PESSIMISTIC_WRITE)` query, `order by createdAt desc, id desc`, first row wins), and the balance is never computed outside the transaction boundary.
- **The `not exists` guard in `createDefaultIfAbsent` must filter on `created_at <= :at`.** Guarding on the account and category alone looks right and is wrong in a way that shows up only as a **500**. A limit installed today has `created_at` of today, so a transaction dated before it finds no limit in effect; without the date in the guard the fallback is not created either, `lockEffectiveOn` finds nothing, and `applyFlag` throws `IllegalStateException`. Verified live: the request returned 500 until the date was added, then 201. The fallback is dated at the epoch precisely so it is in effect for a transaction from before the service ever heard of the account. The scenario `A backdated transaction is measured against the fallback, not a limit installed after it` fails with exactly that exception when the date is removed.
- **Do not "simplify" the default-limit creation into a read-then-write.** `createDefaultIfAbsent` is a native `insert … select … where not exists (…) on conflict do nothing`. The index `ux_expense_limit_default` is partial on `is_default`, so `on conflict` alone only sees the default rows: without the `not exists` guard a client that installed its own 2000 USD limit still gets a 1000 USD default row beside it. Flags are unaffected — the installed limit is the later one and wins — but `GET /limits` then reports a limit nobody asked for, and the specification says the fallback stands in for a limit that was **not** set. The idempotency is unchanged by the guard: two concurrent first transactions race the index, not the guard.
- The rate is resolved **before** the lock is taken: an external call takes seconds, and holding a row lock across it would block every other transaction of the client.

## Where the money logic lives

- **`domain/LimitFlagRule`** is the flag rule as a pure function, and `features/rule/limit_flag_rule.feature` checks it against every row of the truth table in milliseconds without a database. Change the rule there, never inline in a service.
- **`domain/MonthBoundary`** is the only place a timestamp becomes a month. Boundaries use `app.month-timezone` (UTC), never the offset the sender happened to use. The month is also stored on the row as `occurred_month`, so reporting never recomputes it.
- **`service/TransactionService.applyFlag`** is where the lock and the running total meet. The three steps — ensure the limit exists, lock it, read the total — are in that order for a reason: the first exists so that there is something to lock.
- **A transaction stores the limit its flag was computed against**, in `expense_transaction.limit_id`. Resolving it by date at query time would report the wrong limit for past transactions the moment a new one is installed, and `features/api/client_api.feature` asserts exactly that.
- **`assignFlag(Long, Boolean)` rejects nulls.** A null there would set `flag_calculated = true` with a missing half, which is exactly the state `ck_expense_transaction_flag_state` forbids; there is `markFlagUncalculated()` for the honest version of that case.
- **`ledger_sum` and `limit_exceeded` are nullable on purpose**, together with `flag_calculated`. The column is named for the ledger currency, not for USD: it stores `sum × close` in whatever `app.ledger.currency-code` says, and a name that named a currency would quietly start lying the day the ledger moves. Likewise `Conversion.sumInLedger`, `sumInLedgerForMonth` and the `sumInLedger` field on both DTOs — the wire name is `sum_in_ledger` (`@JsonNaming` does the renaming, the Java field keeps its own name), the JSON vocabulary is ours, and the spec fixes only `limit_sum`, `limit_datetime` and `limit_currency_shortname`. A transaction in a currency with no rate is stored rather than dropped, and its flag stays explicitly uncalculated instead of being invented as `false`. A CHECK enforces that `flag_calculated and limit_exceeded is null` is impossible.
- Installing a limit **appends**. A client may install as many limits as they like for the same category — that is how case 1 raises the limit from 1000 to 2000 USD on 10 January. Only *updating* an existing limit is forbidden, so there is deliberately no "limit already exists" error.
- **The automatic 1000 USD fallback is dated at the epoch** (`to_timestamp(0)`), not at "now". A transaction can arrive dated before the service ever heard of the client — the specification's own tables do exactly this — and "now" as the installation date would leave that transaction with no limit to compare against. the backdated scenario in `features/service/limit_flag_scenario.feature` covers it; the first implementation stamped it with the current time and 500 on every backdated request.
- Because of that epoch date, `ReportingService` reports `limit_datetime` as **null** for the automatic fallback. Telling a client 1000 USD was installed on 1 January 1970 would be worse than saying nothing.

## The batch endpoint (optional point 1*)

`POST /api/v1/transactions/batch` takes up to 100 transactions and splits the work into two phases.
**Only the first one is concurrent, and which one it is is the design.**

- **Preparing** resolves the category, the currency and the exchange rate, one virtual thread per
  transaction. The rate is the slow part — an uncached currency costs an external call measured in
  seconds — and the tasks are independent, so there is nothing to gain by ordering them.
- **Storing** runs sequentially, each transaction in its own `TransactionTemplate` transaction. Two
  transactions of one account and category contend for the same limit row, and `PESSIMISTIC_WRITE`
  serialises them regardless; running them on separate threads would pay for scheduling to arrive at
  the queue the lock already imposes. `features/api/client_api.feature` asserts the batch answers the
  same as the same transactions sent one by one.

**A virtual thread does not inherit the caller's transaction**, so `prepare` runs inside an explicit
`TransactionTemplate.execute` rather than reading outside one. `@Transactional` is not an option for
the same reason and a second one: a self-invocation is not proxied.

**`prepare` writes, and that is a real race the batch exposed.** `convert` stores the rate it fetched,
so three KZT transactions in one batch share one provider call through `RateFetchCoordinator` and all
three then write the same row. With the old find-then-write the first inserted it and the other two hit
`ux_currency_rate_base_date` — which surfaces as `UnexpectedRollbackException` from the commit, naming
neither the constraint nor the transaction. `CurrencyRateRepository.upsert` is one
`insert … on conflict do update` statement, so the race has no failure mode to reach. The single-request
path cannot see this: it never runs two writers for the same pair at once.

**Two `Duration` beans make every injection point ambiguous**, and Spring answers that at startup with
`NoUniqueBeanDefinitionException` naming neither candidate. `RateTimeouts` is one record carrying both
bounds, derived from the provider's own timeouts rather than written as numbers.

**`@NotEmpty` and `@Size` on a handler parameter turn on method validation for the whole parameter**, so
a bad amount inside a batch arrives as `HandlerMethodValidationException` rather than as
`MethodArgumentNotValidException` — and Spring answers the first with a `ProblemDetail` that has no
`type` URN. `GlobalExceptionHandler` overrides it so a refused batch is the same shape as any other
error. Declaring the bounds is worth that: without them a batch is unbounded in size and holds the
request thread for as long as the provider takes.

## Currencies

- **The currency code is a verified reference, not a string.** `expense_transaction.currency_id` and `expense_limit.currency_id` are foreign keys to the `currency` lookup, and `currency_rate.base_currency` references `currency(code)`. Never store a raw code in a new column.
- **The schema refuses a lowercase code.** `ck_currency_code_uppercase` and `ck_currency_code_shape` make `kzt` impossible to insert, so a typo cannot become a second currency. The currency-contract feature checks that, and checks that the service resolves a lower-case code from a request to the seeded row rather than storing it.
- **The lookup is seeded with three codes in `V1__init.sql`** — `USD`, which every limit amount refers to, and `KZT`/`RUB`, which the specification names. It used to carry all of ISO 4217: 157 rows of data in a file whose job is to describe a schema, going stale in a file Flyway will not let you rewrite. `CurrencyBootstrap` reads the provider's list at startup and `WeeklyCurrencyImport` weekly; both call one `CurrencyImporter`. **A transaction in an unknown code is refused with 404 until then — that is intended, not an oversight**, and it is what an unquoted code deserves.
- **The import is idempotent in the database, not in the application.** `CurrencyRepository.insertIfAbsent` is one `insert … on conflict do nothing`. An `existsByCode` check followed by a `save` is idempotent for one caller and wrong for two: several replicas start at once, each sees the code missing, and the losers fail on `ux_currency_code`. Every deployment and every weekly sweep runs this, so several callers is the normal case.
- **`CurrencyBootstrap` does not hold the startup.** It hands its work to a virtual thread on `ApplicationReadyEvent`. The alternative is a service that starts only when the rate provider answers, and this project is built so a provider outage is not an outage here. The cost is that a transaction in a currency published seconds ago gets 404 for a moment — the same answer it gets with no provider configured at all.
- **An empty answer from the provider is logged.** The free tier replies with an empty body once its quota is spent, which is the same shape as "no currencies", and a lookup that stops growing should not look like a provider with nothing to add.
- **The currencies swept daily live in the `currency_pair` table, not in `application.yml`.** An operator must be able to add or retire one without a release, and every poll costs money, so what is kept warm is an operational decision rather than a property of the code. `currency_pair.active` drives it; the seed is `KZT` and `RUB`, the bases of the pairs п.3 asks for. `AppProperties` has **no** pairs field any more: adding one back is a regression, not a convenience.
- **`currency_pair` has no quote column, and never had one worth having.** The quote is `LedgerPolicy.currencyCode()` — a single value — and `currency_rate` stores rates without a quote because it is implied. A stored copy would be a second source of truth that can disagree with the configuration, and the only way to reconcile it is a code-level check on every sweep, which is exactly the `ScheduledRatesFetcher` comparison that was removed for that reason. **Do not add `quote_currency` back.** Being absent from the table is not a rejection: an unlisted currency is accepted and fetched on demand.
- **`ck_currency_pair_quote_usd` is gone for a second reason.** A CHECK cannot read a property, so pinning the quote in the schema would mean re-editing an applied migration every time the ledger moves. It went when the column went.
- That subset is about which rates are pre-fetched, **not** which currencies are accepted: a transaction in a currency outside it is still stored, with `flag_calculated = false` when no rate exists.
- Conversion is `ledger_sum = sum × close`, where `close` is units of the ledger currency per one unit of the transaction currency. Multiply first, round once with `HALF_UP` at `app.ledger.amount-scale`: rounding the rate first destroys the only precision the conversion has.
- Lookup order in `ExchangeRateService`: Redis, then the exact day (`close`, falling back to `previous_close` on weekends and holidays), then the most recent rate not later than that day (a Monday close may not be published when the transaction arrives on Tuesday), then one external call. All of them failing means the transaction is stored unflagged, not rejected.
- **The Redis cache is a cache, and every failure mode of it falls through.** `cache/RateCache` catches connection failures and undecodable values and returns empty; `app.cache.enabled=false` skips it entirely, which `RateCacheDisabledTests` covers in its own context because a property cannot be flipped from a scenario. **Do not let an exception escape `RateCache`** — ingestion is the product, and a cache that can fail an upload is not a cache. **Do not cache a miss**: caching "no rate for this day" keeps sending transactions down the paid external path after the sweep has published one.
- **Invalidation is a generation counter, not key deletion.** A cached entry is keyed by the day that was *asked for*, and the rate that answered it may be older — `findLatestOnOrBefore` deliberately answers a question about the 5th with the rate of the 1st. So a write has to retire entries cached under later days too. Each pair carries a counter in its key and `store` increments it; finding those keys otherwise needs a `SCAN` or a tracked set. `app.cache.ttl` is the backstop, not the mechanism. **Invalidate where the row is written**, in `ExchangeRateService.store`, which is also the sweep's path: a test that updated the row with SQL would be testing a cache that can never notice a refresh, which is a real way to build this and therefore a bug to catch rather than a fixture to accommodate.
- **The test cleanup has to cover the cache too.** `StoredWorldCleaner` empties the tables *and* the Redis keys by prefix. It exists because the suites each had their own `@Before` and the cache was the one piece of state those lists missed: the rows were deleted and the answers survived, so a scenario asking for "a currency with no rate anywhere" was answered from the previous scenario's rate. Ten scenarios failed the day the cache landed, and all ten were that.
- Only `base = transaction currency` is stored; the quote is always USD and has no column. The inverse pair is derived by division, and persisting it would let two rows drift apart.

## The rate provider is not what its documentation says

Everything here was found against the live Twelve Data API with a real key, and none of it was
visible in the suite: WireMock answers whatever it is given a stub for, so this path had only ever
been exercised against a provider that was assumed rather than asked.

- **There is no `KZT/USD`.** The symbol does not exist and answers 404 with "symbol or figi
  parameter is missing or invalid", which reads like a malformed request rather than a missing pair.
  `USD/KZT` is there. `TwelveDataRatesProvider` therefore asks for the other side and divides —
  `ExchangeRate.inverted`, in `domain` rather than in the adapter, because a financial rule is not
  the job of a class that speaks HTTP. **Both sides invert, `previous_close` included**: leaving the
  fallback upright mixes an upright close with an inverted one and produces a number belonging to
  neither orientation. A 404 is answered without a retry — asking again cannot create a symbol.
- **There is no `/currency` endpoint either.** Not on any tier; it answers 404 "page not found". The
  first version of the adapter called it anyway, so every startup import finished with nothing. The
  working source is `symbol_search`, which also answers equities and crypto, filtered on the
  LEDGER/OTHER pair shape.
- **Take the OTHER side of that pair.** The search is asked about the ledger currency, so every pair
  is `USD/something` and the base is the same value in every row — reading it yields the ledger
  itself, the filter that removes it removes everything, and an empty import is indistinguishable in
  the log from a provider with nothing new.
- **`previous_close` is not sent at all.** The answer carries exactly `datetime, open, high, low,
  close`. Reading that field produces null on every request, and п.3's rule for a day with no close
  never runs — quietly, because the transaction is stored without a flag, which is the right answer
  to a missing rate and the wrong one to a rate that exists. `fetchOnce` builds the fallback itself
  out of a second request over a ten-day window, and picks the point by comparing dates rather than
  by position: this provider answers newest first, and one answering the other way would quietly
  price the day from the far end of the window.
- **A day with no close comes back with an empty `values`, not a null close.** The provider omits a
  closed day from the series, so an empty answer is the holiday case rather than a failure. Both
  shapes lead to the same fallback; deciding between them would mean deciding which to believe.
- **`400 "No data is available"` is about the data, not the request.** Matched on the body, not on
  the status alone: 400 also covers a request this adapter got wrong, and treating that as "no data"
  turns a bug into a transaction quietly stored without a flag.
- **The history is about a year and that is the plan, not a bug.** Measured: `2024-01-02` no data,
  `2025-06-02` data. Every date in the specification is January or February 2022, and the rate is
  looked up for the **day of the expense**, so `2022-01-30` is what gets asked for. Such a
  transaction answers `limit_calculated: false`, which is honest rather than broken. The free tier is
  also 8 requests a minute, and past it the provider replies with an empty body — the transaction is
  stored unflagged and nothing errors.

## Accounts

- `account_from` is a `varchar(10)` with a digit CHECK, and `account_to` likewise. **There is no account table on purpose.** The specification says exactly one thing about accounts — «строка из 10 цифр» — which describes a field format, not an entity. Normalising accounts would need facts about them (owner, account currency, status) that nobody has specified. Add a table the day something actually needs it, not before.

## Web layer

- **Every handler carries `@Operation`** with a summary and a description that says something the method name does not. `@ApiResponse` documents the non-2xx outcomes, and the description carries the part a reader would otherwise have to guess: which limit a row reports, why a flag may be absent, which date the service chose. Docs are served at `/swagger-ui.html` via `springdoc-openapi-starter-webmvc-ui`.
- **`@RateLimited` marks an endpoint as limited; the numbers live under `app.rate-limits`.** The annotation names a block (`quota = "ingestion"`), and its own values are only a fallback so a newly limited endpoint is never silently unlimited. A configured block always wins over the annotation defaults.
- **The limiter is resilience4j's `RateLimiter` core API, not `@RateLimiter`.** The annotation is an AOP aspect that builds *one limiter per method*, which is a per-method quota, and the quota here is per calling address. `RateLimitInterceptor` builds and caches limiters in a map instead. Same reason for `Retry.of(...)` in `TwelveDataRatesProvider` rather than `@Retry`: neither annotation can key by anything but the method, and the Spring starter would pull AOP in for no gain.
- **Quota properties are named in the limiter's vocabulary** — `limit-for-period` and `limit-refresh-period`, not a token bucket's `capacity` and `refill-tokens`. The old names also described a token bucket, and a limiter that grants N permits per period is a slightly different policy; naming it the way it is enforced keeps the configuration and the code telling the same story.
- **The limiter is per calling address (`ip=` + `getRemoteAddr()`), not per account.** The account travels in the JSON body and an interceptor runs before the body is readable; `getParameter` does not see a body, consuming the stream would leave nothing to parse, and caching it does not help because the cache fills as the controller reads. A per-account quota needs a filter that buffers the body first or an account header — a deliberate change, not something to smuggle into the interceptor.
- Limiters are in memory, so a horizontal scale-out multiplies the effective quota by the instance count. That is right for shedding load per node and wrong for a global cap; do not present it as either.

## Boot 4 gotchas found the hard way

- **Jackson 3 for databind (`tools.jackson.databind`), but annotations stay on `com.fasterxml.jackson.annotation`.** The DTOs use `@JsonFormat`/`@JsonProperty` from the old package name; test code that injects an `ObjectMapper` must import `tools.jackson.databind.ObjectMapper`.
- **No `RestClient.Builder` bean is contributed.** Build it yourself; `TwelveDataRatesProvider` constructs one, and `BddInfrastructureConfiguration` declares one for the HTTP scenarios.
- **`TestRestTemplate` moved to `org.springframework.boot.resttestclient`,** and it is not auto-configured into a `@SpringBootTest`. The end-to-end scenarios use `RestClient` against the random port instead, which the specification explicitly allows.
- **`ResponseEntityExceptionHandler` signatures changed.** `handleMethodArgumentNotValid` is now `(exception, HttpHeaders, HttpStatusCode, WebRequest)` returning `ResponseEntity<Object>`. The old three-argument form silently stops overriding anything.
- A `@RestClient` request without an explicit `Content-Type: application/json` is answered **415** before validation runs, which would make any assertion about validation meaningless.
- A modifying native `@Query` needs **`@Modifying`**; without it Hibernate complains that the statement returned no results. `createDefaultIfAbsent` also needs `clearAutomatically`/`flushAutomatically` so the inserted row is visible to the lock that follows.

## Runtime: the image, and what runs where

- **The image is `eclipse-temurin`, unmodified.** A trimmed `jlink` runtime on a distroless base was tried and reverted: it works and it saves about a third of the pulled size, but it costs a module list that is only correct until the next dependency reaches further, and a wrong entry fails on the first run rather than at the build. That trade was not worth it here.
- **Heap size is `MaxRAMPercentage`, and that is meaningless without `mem_limit`.** A container with no limit sees the host's memory, so "70%" becomes a share of whatever machine it landed on — measured at 11.7 GB on an unconstrained run here, against the 718 MB the same flags produce under `-m 1g`. `UseContainerSupport` is on by default since JDK 10, so the JVM reads the cgroup limit on its own and no `-XX:+UseContainerSupport` is needed. `MaxMetaspaceSize=128m` is a separate cap because the heap percentage does not cover metaspace, and an unbounded one kills a container whose heap was never touched.
- **Verify the heap with `jcmd`/`-Xlog:os+container`, not with `jvm.memory.max`.** Under G1 that metric reports the **old generation only** — it reads ~250 MB on a container whose `MaxHeapSize` is 718 MB. `-Xlog:os+container=debug -XX:+PrintFlagsFinal` prints the `MaxHeapSize` the JVM actually chose.
- **Micrometer registers the Hikari meters as `hikaricp.*`, not `hikari.*`.** A dashboard querying `hikari.connections.pending` shows an empty series rather than an error, so the mistake is invisible. There are **no Tomcat thread meters in Boot 4** at all — only `tomcat.sessions.*`; thread counts come from `jcmd <pid> Thread.print | grep -c http-nio`, which is a diagnostic and not something to scrape.
- **`idle-timeout` must stay below `max-lifetime`**, and the failure is quiet: Hikari *disables* the idle timeout and logs one warning that reads like advice about something else. Set both to 600000 and `idle-timeout` stops existing.
- **`JAVA_TOOL_OPTIONS` carries the JVM diagnostics**, because the JVM reads it itself and that is how a deployment overrides flags without a rebuild. It sets `-XX:+HeapDumpOnOutOfMemoryError -XX:+ExitOnOutOfMemoryError -XX:HeapDumpPath=/app/dumps`. **There is no flag that dumps the heap on a JVM crash** — HotSpot has no such facility, and a native crash is diagnosed from the `hs_err` log that `-XX:ErrorFile` points at the same directory. A dump is as large as the heap and holds credentials, so `/app/dumps` wants a volume.
- **`curl` in the runtime stage is not optional**, because the `HEALTHCHECK` and the CI smoke check both use it and the temurin JRE ships no `wget`. Do not "tidy" the `apt-get` layer away.
- **The actuator `heapdump` endpoint and the Swagger UI are both off outside the `dev` profile.** The dump is a full copy of the process, so exposing it anywhere shared hands over whatever is in memory; `/v3/api-docs` is the other half of the same problem, describing the whole internal surface rather than the two documented APIs. springdoc warns about the second on every production start and is right to. Both are one property away from open in a deployment where that is a decision rather than a default. **Clients get the contract as a build artifact instead** — see the note on `quality.yml` below.
- **`server.shutdown: graceful` with a 30 s phase.** The default cuts in-flight requests immediately, which for this service means interrupting a transaction that may hold `PESSIMISTIC_WRITE` on a limit row: the transaction rolls back, the client gets a 500, and the deployment that caused the restart looks like the outage.
- **`spring.threads.virtual.enabled` is false in `prod`, on purpose.** Virtual threads on Tomcat move the bottleneck to HikariCP: request concurrency becomes unbounded while the pool stays at ten connections, so latency gets *worse* and the cause is invisible on a dashboard. `server.tomcat.threads.max` also stops meaning anything, `jstack` stops showing request threads, and a slow request is no longer killed by `connectionTimeout`. The daily sweep uses virtual threads regardless — that is where they pay off, with no shared pool involved.
- **The pool, the thread count and the timeout are one decision with three numbers**, and they are in `application-prod.yml` together for that reason. Pool 20, threads 40, pool timeout 1 s. The default Tomcat 200 against a pool of 20 allows 180 threads alive doing nothing but waiting for a connection, each holding a stack and a client socket, and it makes overload arrive slowly and look like a bug. With the counts tied together, `hikaricp.connections.pending` and `.timeout` rise exactly when the pool is the constraint. A short timeout is deliberate: past it the caller is told the service is busy rather than parked for five seconds and told the same thing.
- **Every instance holds up to `maximum-pool-size` connections, so `replicas × 20 + 3 + migrations < max_connections`.** At the compose default of 100 that is three replicas, not five. Past the limit the symptom is not an error but a hang: connections queue inside the database while the application reports itself healthy. Raising `max_connections` postpones that rather than fixing it; a pooler removes it and is a separate decision.
- **Redis carries `maxmemory 64mb` and `allkeys-lru` in compose.** The default policy is *noeviction*, so a full Redis refuses rather than evicting — the cache stops helping while still being paid for, and the application correctly falls back to the database.
- **`ScheduledRatesFetcher` opens its transactions with `TransactionTemplate`, not `@Transactional`.** Its `refreshDaily` fans out across network calls, and a self-invoked transactional method is not proxied, so the annotation would silently not apply while still pinning a connection for the whole sweep. The parallel phase is fetching only; the results are written in one batch afterwards so a failure halfway cannot leave the table half refreshed.
- **`refreshDaily` is enabled by `app.scheduling.enabled`, and the test contexts turn it off.** Otherwise the daily sweep and the weekly currency import are registered in every `@SpringBootTest`, and a test that happens to run at the right hour starts calling a paid external API.
- **Which jobs run is a row in `scheduled_job`, not a property.** `@ConditionalOnProperty` is settled while the context registers bean definitions and cannot change afterwards, so a property here would mean restarting the application to avoid one paid call — and a flag in memory would leave the other replicas still paying, which is worse than no flag at all. Each job asks `ScheduledJobService.isEnabled(...)` at the top of its own method and returns. The wakeup costs nothing; the HTTP call is what costs. A missing row means **enabled**: falling back to disabled would let a deleted row silently stop the daily sweep the specification requires. `features/service/currency_sweep.feature` asserts the provider is not called at all, not merely that the lookup did not grow — with an empty list the second assertion would pass against code that paid the credits anyway.

## CI/CD

Two workflows, split by cost. The split is about *your* wait time, not a quota.

| | Trigger | What runs |
|---|---|---|
| `ci.yml` | **every push to any branch**, every PR, `workflow_dispatch` | composite action → `./mvnw -B verify`: compile, tests, Spotless, SpotBugs, ArchUnit, jar artifact |
| `quality.yml` | `workflow_dispatch`, nightly cron (`17 3 * * *`), push to `master` | Docker image build + real startup smoke check + the OpenAPI contract as an artifact |

`ci.yml` runs on all branches so a feature branch is checked before it is merged rather than only afterwards. Its concurrency group is keyed on the ref, so a push to a feature branch cancels that branch's earlier run and leaves a `master` run alone. `quality.yml` has no static-analysis job: both SpotBugs and Spotless are bound to `verify`, so `ci.yml` already covers them and a second job would repeat the work without checking anything new. Neither workflow filters paths, because a push touching only a workflow file is exactly the one that has to prove that workflow still resolves its actions.

- `.github/actions/ci/action.yml` is the composite action holding the shared steps, and it runs nothing but `./mvnw -B verify`. **Spotless and SpotBugs are bound to the `verify` phase in the pom, so that single command is what CI runs, and `scripts/ci.sh` runs the same one.** They used not to be bound, which meant the format was only ever checked locally and eleven unformatted files reached `master`; the script also called `spotless:check` separately, which started a second Maven for a goal the build already performs, and that call is gone.
- **A composite action's `run:` steps need an explicit `shell:`**, and omitting it does not fail that step — the whole action fails to *load*, so the run ends in seconds with a manifest error. Both halves of that shape appeared on the first real push and neither was catchable locally: `scripts/ci.sh` runs the same Maven command and was green throughout. Fixing the first one only revealed the second, which is why the run has to be watched to its first real step rather than judged by its status.
- **A local action needs `actions/checkout` in front of it** — it is a path on the runner, so the repository has to be on disk before it resolves. The error names the action ("Can't find 'action.yml'") and points away from the missing step in front of it.
- **A composite action goes in `steps`, never in `jobs.<id>.uses`.** That key is for reusable *workflows*; pointing it at an action directory produces a run that **fails in zero seconds with no jobs at all**, which reads as "nothing happened" rather than as a mistake. It shipped that way from the commit that introduced the pipeline and was invisible until the repository was actually pushed: locally `./scripts/ci.sh` was green throughout, so the only way to catch it was to watch the first run. **A GitHub run that fails in seconds without a single job is a workflow parse error, not a test failure** — check `gh run view <id> --json jobs`, which comes back empty.
- Run the slow checks on demand from the Actions tab rather than waiting on a commit. Do not add heavy jobs to `ci.yml` to "improve quality".
- The `image` job packages the jar on the runner first (`-DskipTests package`) so the Docker build does not re-run Maven, then starts `postgres:16` and `redis:7` on a Docker network, runs the image and polls `/actuator/health` with `curl`. The image installs `curl` for exactly this reason — the temurin JRE has no `wget`, so the original `wget`-based check failed with "executable file not found".
- **`springdoc-openapi-maven-plugin` is deliberately not used.** It answers a request against `/v3/api-docs`, which means starting the application — and this one refuses to boot without a `DataSource`, so a plain `mvn package` would fail for anyone without a database, and `quality.yml` boots its databases *after* packaging. The contract is produced by a container instead: the `image` job runs a second instance in the `dev` profile on port 8081 (where `api-docs` is on, unlike prod), collects the JSON, and uploads it as the `openapi-contract` artifact. Two containers rather than one because the smoke check must keep testing the profile the deployment actually uses.
- The `Dockerfile` is a two-stage build; locally `docker build -t financial-transactions:local .` takes several minutes the first time.
- If a run sits queued with no log output, check `githubstatus.com` before suspecting the code.

## Repo housekeeping

`pom.xml` uses **tab** indentation (Initializr default); match it. `HELP.md` is listed in `.gitignore`, so it is never committed — do not rely on it as documentation.

`.gitignore` ignores `.env*` and then re-admits `.env.example` with a negation, so a future `.env.production` is covered automatically while the template stays tracked. **`.env.example` must never contain real credentials.**

Known gaps: a single rates provider with no failover, no MCP server over this API (optional in the spec), the cache is not warmed. The repository is **private**, so requirement 10 is not formally met — a deliberate choice for code carrying a paid provider integration, noted in `docs/known-gaps.md` so it is a stated decision rather than a silent omission. The skills at `.agents/skills/` (with `.claude/skills` symlinked to `.agents/skills`, so both harnesses find them) are each deliberately about one area rather than a summary of `AGENTS.md`: `changing-limit-behaviour` covers the flag rule, the lock and the fallback; `changing-swept-currencies` covers `currency_pair`, the rate lookup chain and the switches in `scheduled_job`; `working-with-mcp` covers the two harness configs, the read-only PostgreSQL server and the git rules.

**The period without commits cost real work once.** Every file was untracked, so an `rm -rf src/test/java/com/idfinance` — meant for a fixtures package inside that tree — took the twenty-one test sources with it and there was nothing to restore from. They came back by decompiling `target/test-classes`, which recovered the logic and lost the comments, and the surviving `features/*.feature` files were what made the behaviour verifiable afterwards. Before any `rm -rf` inside `src/`, check `find <path> -type f | wc -l` and `git ls-files <path>`; `git status` on an untracked tree tells you nothing useful.

**OpenCode and Claude Code read different config files, and only one of them works here.** `.mcp.json` at the repository root is the **Claude Code** format — `mcpServers`, an `env` block, `command` as separate keys. OpenCode ignores it entirely: `opencode mcp list` answers *No MCP servers configured*. OpenCode wants the key `mcp`, a `"type": "local" | "remote"` on every entry, `command` as **one array** combining executable and arguments, and `environment` rather than `env`. Both files are kept deliberately, one per host, and the OpenCode one is `opencode.json` in the project root (Context7 only, being the server tied to this repository's dependencies) plus `~/.config/opencode/opencode.jsonc` (GitHub and PostgreSQL, being personal and carrying a token).

**`@crystaldba/postgres-mcp` no longer exists on npm** — the registry answers 404, and a `command` that cannot resolve fails as a server that "exited with code 1" rather than as a missing package. The replacement is `@contextflo/postgres-mcp`, a read-only server. The PostgreSQL connection is configured with `default_transaction_read_only=on` and `statement_timeout=5000` on purpose: a writable agent connection to a development database is how a table disappears, and the guard belongs in the config rather than in the instructions. **The `readonly` role has to exist** — created by hand, since `docker-compose.yml` only seeds the application role.

**The GitHub MCP server has a remote and a local form, and the remote one needs Copilot.** `https://api.githubcopilot.com/mcp/` answers **403** to a plain `gh` token, which works fine against `api.github.com`. The local Docker image (`ghcr.io/github/github-mcp-server`) takes the same token and is what is configured. The token itself comes from `gh auth token` in `config.fish`, so it is never written to a config file or to the repository — and the server must see it in the environment **at startup**, or it silently falls back to a device-code flow that waits for a browser.

**`GITHUB_TOOLSETS` is not optional.** The server registers far more tools than a session can carry; `context,repos,issues,pull_requests,actions` is what this project actually needs, and it is set in the server's environment rather than after the fact.
