---
name: changing-swept-currencies
description: Use when changing which currencies the daily sweep pre-fetches, the currency_pair table, the currency lookup, or how an exchange rate is resolved and stored. Also use when switching a scheduled job on or off, including the weekly currency import, or before adding a column to currency_rate.
---

# Changing the swept currencies

Not the flag, but the other thing an operator asks for next, and the answer is not where people
expect it to be.

## Two branches, and neither is a rejection

The currencies the daily sweep pre-fetches live in the **`currency_pair` table**, not in
`application.yml`. They are an operational decision — every sweep iteration is a paid provider
request — and they are in a table so an operator can add or retire one without a release.

**Being absent from this table is not a rejection.** A currency that is not tracked is still
accepted in a transaction, and its rate is fetched on demand by `ExchangeRateService.fetchAndStore`.
The table only says what has *already been pre-fetched*.

| Where the currency sits | Who fetches the rate | When |
|---|---|---|
| In `currency_pair`, `active = true` | `ScheduledRatesFetcher.refreshDaily` | once a day, prepaid |
| Anywhere else | `ExchangeRateService` | on the transaction that needs it |

## Where it lives

| Concern | Where |
|---|---|
| Which currencies are swept | `currency_pair`, editable via `/api/v1/currency-pairs` |
| Which currencies are accepted at all | `currency`, seeded by the migration |
| Whether each job runs | `scheduled_job` |
| How a rate is found | `ExchangeRateService` — Redis, then the exact day, then the latest not after it, then the provider |
| How a rate is written | `ExchangeRateService.store` — this is the path the daily sweep uses too |

## Changing the swept set

### Through the API

```
GET  /api/v1/currency-pairs          # every tracked currency, active or not
PUT  /api/v1/currency-pairs/{code}   # body: {"active": true|false}
```

`activate` adds the row if the currency was not tracked; `deactivate` on an untracked currency is
a **404**, not a silent insert — a row created inactive would claim a currency had been tracked and
would answer a typo with a 200. Setting the value a row already has is a **200**, so a retry
succeeds. There is no access control anywhere in this prototype, so this endpoint is the first
thing to remove before the service goes anywhere shared.

### Through SQL

For an environment you only have shell access to. Note that the PostgreSQL MCP server is
`default_transaction_read_only=on`, so an agent using it **cannot** do this — it needs psql, or a
migration.

```sql
-- start tracking one the seed does not list (the currency must already exist in `currency`)
insert into currency_pair (base_currency, active) values ('EUR', true)
on conflict (base_currency) do update set active = true;

-- retire one, keeping the row and the rates already fetched
update currency_pair set active = false where base_currency = 'KZT';

-- what the sweep is paying for right now
select p.base_currency, p.active, p.created_at from currency_pair p order by p.base_currency;
```

`base_currency` is a foreign key to `currency(code)`, so a code that is not in the lookup cannot be
inserted at all — which is why the service returns 404 rather than storing a row it could never
sweep.

## Switching a scheduled job on or off

Each job asks `ScheduledJobService.isEnabled(...)` at the top of its own method and returns if the
answer is no. That is a row in `scheduled_job`, not a property:

```sql
-- the weekly currency import: stop paying the provider's credits for it
update scheduled_job set enabled = false where name = 'weekly-currency-import';

-- and back on. No restart either way.
update scheduled_job set enabled = true where name = 'weekly-currency-import';
```

**The cron is not switched off.** `@Scheduled(cron = "0 30 4 * * MON")` stays registered and the
method still runs every Monday at 04:30 — it returns before reaching the provider. Waking a JVM up
once a week costs nothing; the HTTP call is what costs credits, and that is what this skips.

A **missing row means enabled**. Falling back to disabled would let a deleted row silently stop the
daily sweep the specification requires.

| Row | What it controls | Why it is separate |
|---|---|---|
| `daily-rates` | the daily sweep of the rates | the specification requires it, so it ships enabled |
| `weekly-currency-import` | re-reading the provider's currency list | costs credits and changes about once a year, so a free plan wants it off |

`app.scheduling.enabled` is a different switch and a different question: whether *any* scheduled
work runs at all. It is a `@ConditionalOnProperty`, which is settled when the context starts, so it
means a restart — which is why it is right for "no scheduler in a test" and wrong for "stop paying
for a call".

## The ways a change here goes wrong

**The rate is looked up for the first day of the month**, not for the transaction date, because
`TransactionService` passes `occurredMonth` to the conversion. A rate stored for the 3rd is
therefore not found by a transaction on the 5th. Two scenarios in
`features/service/conversion_boundaries.feature` exist to pin this down.

**The rate on a transaction is a snapshot, not a reference.** `applied_rate` and `rate_date` are
stored on the row because `ExchangeRateService.store` *updates* the `currency_rate` row in place. A
foreign key to `currency_rate` would point at a number that has since changed.

**Do not cache a miss.** Caching "no rate for this day" keeps sending transactions down the paid
external path after the sweep has published one. Invalidation is a generation counter, not key
deletion, because `findLatestOnOrBefore` deliberately answers a question about the 5th with the
rate of the 1st, so a write has to retire entries cached under later days too.

**Never add `quote_currency` back to `currency_pair`.** The quote is `LedgerPolicy.currencyCode()`, a
single value, and a stored copy would be a second source of truth that can disagree with it.

**`@ConditionalOnBean` on a `@Component` is fragile.** `WeeklyCurrencyImport` carries one and its
behaviour depends on the order beans are scanned, unlike `@ConditionalOnProperty`. The bean also
disappears entirely when no provider URL is configured, because with `NoRatesProvider` there is
nothing to list.

## Before you start

```bash
export JAVA_HOME=/usr/lib/jvm/java-27-openjdk
./mvnw test -Dtest=ServiceFeatureTest   # conversion_boundaries, currency_sweep
./mvnw test -Dtest=ApiFeatureTest       # currency_contract, currency_pairs
```

`features/service/currency_sweep.feature` is the one that writes to the `currency` table, so it
restores the lookup afterwards and sets the `scheduled_job` rows back on in its `@After`. If you add
a scenario that switches a job off, restore it there too — the flag is shared by every suite, and a
row left off makes a later scenario's sweep silently do nothing.