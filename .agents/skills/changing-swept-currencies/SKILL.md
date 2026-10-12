---
name: changing-swept-currencies
description: Use when changing which currencies the daily sweep pre-fetches, the currency_pair table, the currency lookup, or how an exchange rate is resolved and stored. Also use when switching a scheduled job on or off, including the weekly currency import, or before adding a column to currency_rate.
---

# Changing the swept currencies

The `currency_pair` table, not `application.yml`, holds the currencies the daily sweep pre-fetches.
Each sweep iteration is a paid provider request, so the set is an operational decision and lives
in a table an operator can edit without a release.

**Absence from `currency_pair` is not a rejection.** An untracked currency is still accepted in a
transaction, and its rate is fetched on demand. The table records what has already been pre-fetched.

| Where the currency sits | Who fetches the rate | When |
|---|---|---|
| In `currency_pair`, `active = true` | `ScheduledRatesFetcher.refreshDaily` | once a day, prepaid |
| Anywhere else | `ExchangeRateService` | on the transaction that needs it |

## Files

| Concern | Where |
|---|---|
| Which currencies are swept | `currency_pair`, via `/api/v1/currency-pairs` |
| Which currencies are accepted | `currency`, seeded with three codes, filled by `CurrencyImporter` |
| Whether each job runs | `scheduled_job` |
| How a rate is found | `ExchangeRateService`: Redis, exact day, latest not after, provider |
| How a rate is written | `ExchangeRateService.store`, which the daily sweep also uses |

## Changing the swept set

### Through the API

```
GET  /api/v1/currency-pairs          # every tracked currency, active or not
PUT  /api/v1/currency-pairs/{code}   # body: {"active": true|false}
```

`activate` inserts the row if the currency was not tracked. `deactivate` on an untracked currency
is a **404**, not a silent insert — an inactive row would claim a currency had been tracked and
answer a typo with 200. Setting the value a row already has is **200**, so a retry succeeds. There
is no access control in this prototype; this endpoint is the first thing to remove before the
service is exposed.

### Through SQL

For an environment with shell access only. The PostgreSQL MCP server runs
`default_transaction_read_only=on`, so an agent using it cannot run these — use psql or a
migration.

```sql
-- start tracking one the seed does not list; the currency must already exist in `currency`
insert into currency_pair (base_currency, active) values ('EUR', true)
on conflict (base_currency) do update set active = true;

-- retire one, keeping the row and the rates already fetched
update currency_pair set active = false where base_currency = 'KZT';

-- what the sweep is paying for
select p.base_currency, p.active, p.created_at from currency_pair p order by p.base_currency;
```

`base_currency` is a foreign key to `currency(code)`, so a code absent from the lookup cannot be
inserted at all. That is why the service returns 404 instead of storing a row it could never
sweep.

## Switching a scheduled job on or off

Each job calls `ScheduledJobService.isEnabled(...)` at the top of its own method and returns if the
answer is no. The switch is a row in `scheduled_job`, not a property:

```sql
update scheduled_job set enabled = false where name = 'weekly-currency-import';
update scheduled_job set enabled = true  where name = 'weekly-currency-import';
```

The cron stays registered and the method still runs; it returns before reaching the provider.
Waking a JVM once a week costs nothing, the HTTP call is what costs credits.

A **missing row means enabled**. Falling back to disabled would let a deleted row silently stop the
daily sweep the specification requires.

| Row | What it controls | Why separate |
|---|---|---|
| `daily-rates` | the daily sweep of rates | the specification requires it, so it ships enabled |
| `weekly-currency-import` | re-reading the provider's currency list | costs credits and changes about once a year |

`app.scheduling.enabled` is a different question: whether any scheduled work is registered at all.
It is a `@ConditionalOnProperty`, settled at context startup, so it means a restart. That makes it
right for "no scheduler in a test" and wrong for "stop paying for a call".

## Defects that pass review

**The sweep runs once per context, and that is the lock doing its job.** `refreshDaily`
carries `lockAtMostFor = "PT10M"`, and ShedLock keeps a process-wide registry of locks it
has taken — it consults that before touching the database at all. A second call in the
same JVM is therefore refused silently: no log line, no error, the method body simply
not invoked. `features/service/daily_rate_sweep.feature` is one scenario for exactly that
reason, and it was one feature that could not be written as three scenarios until this
was found. **Do not add `shedlock` to `StoredWorldCleaner`**: deleting the row while the
registry still believes it exists leaves the provider skipping the insert, attempting an
update that matches nothing, and refusing the lock — a state it cannot recover from.

**`PricingDay` shortens the weekend before anyone is asked, and holidays stay two
requests.** A Saturday asked for directly costs two provider calls — the day's own, then
the adapter's ten-day fallback window — where the Friday before costs one. Both the sweep
and `TransactionService` route through the rule, because a row one of them writes and the
other reads is the exact defect they had when the conversion asked for the first of the
month. It is an exchange-rate rule, not an instrument rule: `currency_pair` is
operator-editable and the lookup carries crypto and equities, so a seven-day instrument
must not be shortened to Friday.

**The weekend rule changes no answer, only the number of requests.** `findLatestOnOrBefore`
already prices a Saturday by Friday, so a scenario asserting the rate passes with or
without `PricingDay`. Only the day the rate is *recorded* as coming from tells them apart,
and only because a stub answers with the date it was asked for. Both features make that
assertion, and neither asserts the rate alone.

**A column name in an applied migration is not a detail.** `V2` created the lock table
with `locked_until` and ShedLock writes `lock_until`, so `@SchedulerLock` failed on entry
to the daily sweep in every environment: the table existed, the migration succeeded, the
context started, and the job had never once run. Nothing caught it because nothing called
`refreshDaily`. `V6__shedlock_lock_until.sql` is the fix, and the lesson is that a schema
and a library have to agree on names — the same failure mode `SchedulerLockConfig` warns
about for the table name, where the mismatch is equally invisible until the first run.

**`applied_rate` and `rate_date` are a snapshot, not a reference.** They are stored on the
transaction because `ExchangeRateService.store` updates the `currency_rate` row in place. A foreign
key would point at a number that has since changed.

**Do not cache a miss.** Caching "no rate for this day" keeps sending transactions down the paid
path after the sweep has published one. Invalidation is a generation counter rather than key
deletion, because `findLatestOnOrBefore` answers a question about the 5th with the rate of the 1st,
so a write must retire entries cached under later days as well.

**Never add `quote_currency` to `currency_pair`.** The quote is `LedgerPolicy.currencyCode()`, a
single value. A stored copy is a second source of truth that can disagree with the configuration.

**`@ConditionalOnBean` on a `@Component` is fragile.** `WeeklyCurrencyImport` carries one and its
behaviour depends on bean scan order, unlike `@ConditionalOnProperty`. The bean also disappears
entirely when no provider URL is configured, because with `NoRatesProvider` there is nothing to
list.

**The provider does not do what its documentation says.** `KZT/USD` does not exist and answers
404; `USD/KZT` does. There is no `/currency` endpoint on any tier. `previous_close` is never sent —
the answer carries exactly `datetime, open, high, low, close`, so `fetchOnce` builds the fallback
itself from a ten-day window, choosing the point by comparing dates rather than by position. The
history is about a year on the free tier. Details and measurements: `docs/currencies.md` and
`docs/assumptions.md`.

## Commands

Detect the JDK. Do not hardcode a path: the pom targets 25, CI installs 25, and any LTS from 21 to
25 is acceptable. A version outside 21–25 is a wrong answer, not a newer JDK.

```bash
java -version 2>&1 | head -1
ls -1 /usr/lib/jvm/
```

`./scripts/ci.sh` pins a usable JDK itself when `JAVA_HOME` has no `javac`.

```bash
./scripts/ci.sh --fix                          # reformat instead of checking
./mvnw test -Dtest=ServiceFeatureTest          # conversion_boundaries, currency_sweep
./mvnw test -Dtest=ApiFeatureTest              # currency_contract, currency_pairs, rates_fallback
./mvnw test -Dtest=ServiceFeatureTest -Dcucumber.filter.name="currency"
```

`features/service/currency_sweep.feature` is the feature that writes to the `currency` table. It
restores the lookup and the `scheduled_job` rows in its `@After`. A scenario that switches a job off
must restore it there too: the flag is shared by every suite, and a row left off makes a later
scenario's sweep silently do nothing.

## Writing the request that triggers this skill

State the operational outcome and the authority.

Weak: "add EUR to the sweep". The agent may edit `application.yml`, which the table above
explicitly rules out, and may not add the row at all.

Strong: "Stop pre-fetching KZT; the deployment pays per poll. Keep the row and the rates already
fetched — `active = false`, not a delete."

Checklist:

- Say whether the change is about what is swept, what is accepted, or how a rate is found. They are
  three different tables.
- Say whether a code must exist in `currency` first, if the change involves a new currency.
- Say whether the change is paid for on every run. That decides whether it belongs in
  `currency_pair`, `currency`, or `scheduled_job`.
- Ask for the scenario, not just the code.

## Keeping the context small

- Read `ExchangeRateService.java` and the one feature. Do not read the whole `client` package: the
  provider contract is in `docs/currencies.md`.
- Filter by scenario name before by suite. `currency_sweep` and `currency_pairs` are in different
  suites and each takes under a minute.
- For a database question use the MCP PostgreSQL server rather than starting the application. It is
  read-only, so a request to change a row has to become a migration or a psql command — say which
  one you want rather than letting it be discovered.