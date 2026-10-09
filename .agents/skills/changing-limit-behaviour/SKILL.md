---
name: changing-limit-behaviour
description: Use when changing how the monthly limit flag is decided — the flag rule, the month boundary, the running total, the row lock, the automatic 1000 USD fallback, or anything that stores or reports a limit. Also use before adding a repository method that aggregates transactions, or before touching a transaction's amount, category or date.
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