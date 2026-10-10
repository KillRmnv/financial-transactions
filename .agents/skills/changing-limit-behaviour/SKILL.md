---
name: changing-limit-behaviour
description: Use when changing how the monthly limit flag is decided — the flag rule, the month boundary, the running total, the row lock, the automatic 1000 USD fallback, or anything that stores or reports a limit. Also use before adding a repository method that aggregates transactions, or before touching a transaction's amount, category or date. Also use when changing which currency pairs the daily sweep pre-fetches, or when switching the weekly currency import on or off.
---

# Changing limit behaviour

The flag is the one thing in this repository that is graded by a table, not by taste. Read the
table before you write anything: `features/service/limit_flag_scenario.feature` holds all eight
rows of it, and `Тестовое_задание_Junior_Java_разработчика_2026.md` is the specification it came
from. A change that makes a scenario fail is not necessarily wrong — but it is never right to
edit the scenario to make it pass.

## The rule in one line

```
limit_exceeded = (sum of the month's transactions, including this one) > (the limit in effect on
                 the transaction date)
```

"In effect" means the latest limit with `created_at <= occurred_at`. An exactly-zero remaining
balance is **not** exceeded: `2000 > 2000` is false.

## Where the money lives

| Concern | File |
|---|---|
| The rule as a pure function | `domain/LimitFlagRule` |
| Where a timestamp becomes a month | `domain/MonthBoundary` |
| The lock, the running total, the flag assignment | `service/TransactionService.applyFlag` |
| The effective limit, locked | `ExpenseLimitRepository.lockEffectiveOn` |
| The month total | `ExpenseTransactionRepository.sumInLedgerForMonth` |
| The limit the flag was computed against | `expense_transaction.limit_id` |
| The truth table | `features/service/limit_flag_scenario.feature` |

Change the rule in `domain/LimitFlagRule` and nowhere else. An inline comparison in a service is
the mistake this file exists to prevent.

## The three ways a change here goes wrong

**1. The limit reaches back before it was installed.** A limit applies from its installation
datetime forward. Installing a new limit does not change the flag on earlier transactions, and it
does not reset what the month has already spent — on 10.01 the remaining balance is 900, not 2000.
Both halves of that sentence are scenarios.

**2. The running total is read outside the lock.** Two concurrent posts for one account and
category must not observe the same total. `applyFlag` does three things in this order for a
reason: ensure the limit exists (so there is something to lock), lock it, read the total. The rate
is resolved *before* the lock, because an external call under a row lock blocks every other
transaction of the client.

**3. The fallback is dated at "now".** It is dated at the epoch. A transaction can arrive dated
before the service ever heard of the account, and "now" would leave it with no limit to compare
against. `createDefaultIfAbsent` is a native `insert … on conflict do nothing`; never simplify it
into a read-then-write, or two concurrent first transactions both create a limit.

## Before you start

```bash
export JAVA_HOME=/usr/lib/jvm/java-27-openjdk
./mvnw test -Dtest=ServiceFeatureTest      # the truth table, in about 30 s
```

`SettableClock` is how a scenario places an event in time. Set the clock rather than reaching
into a repository — and note that the early scenarios all move the clock forward, which is how a
backdated-transaction defect stayed hidden until the scenario that moved it backwards arrived.

## After you change something

```bash
./scripts/ci.sh          # format check + all tests + SpotBugs
```

A single scenario: `./mvnw test -Dtest=ServiceFeatureTest -Dcucumber.filter.name="backdated"`.

## What a passing run does not tell you

- The truth table is checked without a database, in milliseconds. A change to the flag rule that
  keeps it green has not been checked against the lock or the month boundary yet — run the whole
  service suite.
- `sumInLedger` is null when no rate exists, and the flag stays uncalculated rather than being
  invented as `false`. A scenario that posts only USD will never see this path.
- `expense_transaction.limit_id` is stored, not resolved by date at query time. A report that
  starts resolving by date will report the wrong limit for past transactions the moment a new one
  is installed — `features/api/client_api.feature` asserts the stored behaviour.

## Changing which currency pairs are swept

Not limit behaviour, but it is in this skill because it is the other thing an operator asks for
next and because the answer is not where people expect it to be.

The currencies the daily sweep pre-fetches live in the **`currency_pair` table**, not in
`application.yml`. They are an operational decision — every sweep iteration is a paid provider
request — and they were put in a table so an operator can add or retire one without a release.

**Being absent from this table is not a rejection.** A currency that is not tracked is still
accepted in a transaction, and its rate is fetched on demand by
`ExchangeRateService.fetchAndStore`. The table only says what has *already been pre-fetched*.
Two branches, both deliberate:

| Where the currency sits | Who fetches the rate | When |
|---|---|---|
| In `currency_pair`, `active = true` | `ScheduledRatesFetcher.refreshDaily` | once a day, prepaid |
| Anywhere else | `ExchangeRateService` | on the transaction that needs it |

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

For an environment you only have shell access to. Note that the `postgres-mcp` server in `.mcp.json`
is `default_transaction_read_only=on`, so an agent using it **cannot** do this — it needs psql, or a
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

### Two switches, different questions

| Property | Question it answers | Default |
|---|---|---|
| `app.scheduling.enabled` | should *any* scheduled work run (a test must answer no) | on |
| `app.currencies.weekly-import.enabled` | should we spend provider credits re-reading the currency list | on |

`WeeklyCurrencyImport` calls `/currency`, which costs credits and changes about once a year, while
the daily rate sweep is what the specification requires. A deployment on a free plan turns the
second one off and keeps the first. Turning it off costs one thing: an ISO 4217 code introduced
after the seed stays unaccepted until a migration adds it, because that bean and the migration are
the only two writers of the `currency` lookup. KZT and RUB are seeded, so nothing the assignment
asks for is affected.

The flag is a `@ConditionalOnProperty` on the bean, not a check inside the method: off means the
cron is never registered. `WeeklyCurrencyImportDisabledTests` proves the bean is absent and that
the daily sweep survives it, because a flag that only disables half of what it looks like it
disables is worse than no flag.