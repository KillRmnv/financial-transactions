---
name: changing-limit-behaviour
description: Use when changing how the monthly limit flag is decided — the flag rule, the month boundary, the running total, the row lock, the automatic 1000 USD fallback, or anything that stores or reports a limit. Also use before adding a repository method that aggregates transactions, or before touching a transaction's amount, category or date.
---

# Changing limit behaviour

The flag is the only part of this repository specified by a table rather than by taste. Read the
table before writing anything: `features/service/limit_flag_scenario.feature` contains all eight
rows. A failing scenario is not automatically wrong, and editing a scenario to make it pass is
always wrong.

## The rule

```
limit_exceeded = (sum of the month's transactions, including this one)
               > (the limit in effect on the transaction date)
```

"In effect" means the latest limit with `created_at <= occurred_at`. A remaining balance of
exactly zero is not exceeded: `2000 > 2000` is false.

## Files

| Concern | File |
|---|---|
| The rule as a pure function | `domain/LimitFlagRule` |
| Timestamp to month | `domain/MonthBoundary` |
| Lock, running total, flag assignment | `service/TransactionService.applyFlag` |
| Effective limit, locked | `ExpenseLimitRepository.lockEffectiveOn` |
| Month total | `ExpenseTransactionRepository.sumInLedgerForMonth` |
| Limit the flag was computed against | `expense_transaction.limit_id` |
| Truth table | `features/service/limit_flag_scenario.feature` |

Change the rule in `domain/LimitFlagRule`. An inline comparison in a service is the defect this
file exists to prevent.

## Three defects that pass review

**1. The limit reaches back before its installation datetime.** A limit applies forward only.
Installing a new limit does not change flags on earlier transactions and does not reset the month's
spending: on 10.01 the remaining balance is 900, not 2000.

**2. The running total is read outside the lock.** Two concurrent posts for one account and
category must not observe the same total. `applyFlag` runs three steps in this order — ensure the
limit exists, lock it, read the total — because the first exists so there is something to lock. The
rate is resolved before the lock: an external call held under a row lock blocks every other
transaction of the client.

**3. The fallback is dated at "now".** It is dated at the epoch. A transaction can arrive dated
before the service knew the account. `createDefaultIfAbsent` is
`insert … select … where not exists (… created_at <= :at) on conflict do nothing` and must not be
simplified into a read-then-write, which lets two concurrent first transactions both create a limit.

**4. The `not exists` guard loses its date.** Guarding on the account and category alone looks
right and fails as a **500**, not as a wrong flag: a limit installed today has a `created_at` of
today, so a transaction dated before it finds no limit in effect; without the date the fallback is
not created either, `lockEffectiveOn` finds nothing, and `applyFlag` throws `IllegalStateException`.
The `not exists` and the `on conflict` are two different guards and both are load-bearing: the
partial index `ux_expense_limit_default` covers default rows only, so `on conflict` alone would
leave a 1000 USD default sitting beside an installed 2000 USD limit — harmless to flags, but
`GET /limits` then reports a limit nobody asked for.

## Commands

Detect the JDK before building anything. Do not hardcode a path, and do not read the two installed
versions as "the pom wants 25, so 27 is wrong" — on this machine **JDK 27 is the only usable one**,
because `javac` 21 rejects `--release 25` outright. CI installs 25 because that is what the pom
targets, and a difference that only shows up there is a compilation or annotation-processing
failure with no useful context.

```bash
java -version 2>&1 | head -1           # what is on PATH
ls -1 /usr/lib/jvm/                    # what else is installed
```

`./scripts/ci.sh` pins a usable JDK itself when `JAVA_HOME` has no `javac`, so for anything that
runs the build it is enough to call it:

```bash
./scripts/ci.sh                  # format check + all tests + SpotBugs, ~2 min
./scripts/ci.sh --fix            # reformat instead of checking
./scripts/ci.sh -Dtest=Foo       # pass Maven arguments through
```

Targeted runs, after exporting the JDK that can compile `--release 25`:

```bash
./mvnw test -Dtest=ServiceFeatureTest                      # truth table against PostgreSQL, ~30 s
./mvnw test -Dtest=LimitFlagRuleFeatureTest                # pure functions, no DB, ~0.2 s
./mvnw test -Dtest=ServiceFeatureTest -Dcucumber.filter.name="backdated"
./mvnw test -Dtest=ServiceFeatureTest#ScenarioName#methodName
```

`SettableClock` places an event in time. Set the clock rather than writing rows directly. The
early scenarios all moved it forward, which is how a backdated-transaction defect stayed hidden.

## What a green run does not prove

- The pure-function suite runs without a database in milliseconds. A change that keeps it green has
  not been checked against the lock or the month boundary; run the service suite.
- `sumInLedger` is null when no rate exists and the flag stays uncalculated rather than becoming
  `false`. A scenario posting only USD never reaches that path.
- `limit_id` is stored on the row rather than resolved by date at query time. A report that starts
  resolving by date reports the wrong limit for past transactions as soon as a new limit is
  installed; `features/api/client_api.feature` asserts the stored behaviour.

## Writing the request that triggers this skill

State the observable change, not the file you think needs editing.

Weak: "fix the limit logic in TransactionService". The agent has to guess which of the three
defects above applies, and may edit a scenario instead.

Strong: "A limit installed on 2022-01-10 must not change the flag on a transaction from 2022-01-05.
The scenario for that is missing — add it, run it, and fix the code so it passes."

Checklist for a request in this area:

- Name the observable behaviour in one sentence, with numbers where the specification has them.
- Say which scenario to add or run. If none exists, say so explicitly — that is a deliverable.
- Say whether the specification, an existing scenario, or a production incident is the authority.
- Do not pre-write the fix. Pre-written fixes get applied to the wrong layer.

## Keeping the context small

- Read `domain/LimitFlagRule.java` and the scenario file. Do not read the whole `service` package
  to find out where the rule lives — the table above answers that in seven lines.
- One scenario at a time: `-Dcucumber.filter.name=...` before `-Dtest=...`. A full service run is
  30 seconds and tells you nothing you did not already know.
- Quote error output, do not re-run to reproduce it. A failed build pasted in full is cheaper than
  the same build observed twice.
- Do not paste a feature file to ask where something is tested; `docs/test-cases.md` is the index,
  and it is already grouped by feature.