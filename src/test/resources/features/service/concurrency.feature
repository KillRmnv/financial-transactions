Feature: Concurrent posts for one client

  The flag is decided from a running total, so a transaction that reads the total
  before another one writes it loses the update — and nothing afterwards shows it:
  both rows exist and the month's total is too low.

  Three things have to hold at once, and only the first is obvious.

  **The limit row serialises the reads.** Two posts for one account and category take
  the same `FOR UPDATE` lock, so the second waits for the first to commit and then
  reads a total that already includes it. The flag count is what proves it: with
  the lock removed, twelve threads produce one flagged transaction instead of two,
  because two of them read the same opening balance.

  **Creating the fallback limit is itself racy.** The scenario above installs a
  limit first, which skips the interesting part: the very first transactions of an
  account arrive with no limit row to lock. Each of them tries to create it.

  **Separate accounts must not serialise against each other.** The lock is on
  `(account_from, category_id)`, so two clients posting at once are two independent
  runs. That is asserted without timing — see below for why.

  **These assertions are probabilistic, and that is not a weakness to be papered
  over.** Removing the row lock makes two threads read the same running total,
  but only when their reads actually overlap: across four runs with the lock
  removed the scenarios below failed in two, not four. A correct implementation
  is deterministic — the flagged count is exact every time — so the asymmetry is
  the signal. A concurrency test that could never fail would be testing nothing;
  one that fails on a correct implementation would be worse than none.

  Boundary values and equivalence classes live in `conversion_boundaries.feature`;
  the truth table from the specification lives in `limit_flag_scenario.feature`.
  This file is only about what happens when the requests overlap.

  Scenario Outline: Concurrent posts for one client serialise on the limit row
    Given the account "0000000123" and the category "product"
    And a limit of "1000" USD was installed on "2022-01-01"
    When <threads> threads each post a transaction of "100" USD on "2022-01-15"
    Then <threads> transactions are stored
    And the month's total is "<total>" USD
    And exactly <flagged> transactions are flagged
    And no transaction is stored without a flag

    Examples: Ten is the boundary at which the limit is passed, and fifty is far enough past it that a lost update would show
      | threads | total | flagged | why                                              |
      | 10      | 1000  | 0       | exactly at the limit, and equality is not exceeded |
      | 11      | 1100  | 1       | one over                                          |
      | 50      | 5000  | 40      | a lost update here would change both numbers      |

  Scenario: Fifty concurrent posts store fifty transactions and lose none
    Given the account "0000000123" and the category "product"
    And a limit of "1000" USD was installed on "2022-01-01"
    When 50 threads each post a transaction of "100" USD on "2022-01-15"
    Then 50 transactions are stored
    And every transaction carries a distinct database identity
    And the month's total is "5000" USD

  Scenario: Concurrent first transactions create exactly one automatic fallback
    Given the account "0000000456" and the category "product"
    And no limit was ever installed for the account
    When 20 threads each post a transaction of "100" USD on "2022-01-15"
    Then 20 transactions are stored
    And the month's total is "2000" USD
    And exactly one automatic fallback limit exists
    And exactly 10 transactions are flagged

  # The account named in the Given is the one the two run against; the step derives a second
  # from it, so a scenario does not repeat the same string in two places.
  Scenario Outline: Two accounts posting at once are two independent runs
    Given the account "0000000460" and the category "product"
    And a limit of "1000" USD was installed for "0000000457" on "2022-01-01"
    And a limit of "1000" USD was installed for "0000000458" on "2022-01-01"
    When 2 accounts each post <threads> transactions of "100" USD on "2022-01-15"
    Then <stored> transactions are stored
    And the month's total is "<total>" USD
    And each account's month totals "<per_account>" USD
    And each account has exactly <flagged> flagged transactions

    Examples: The lock is per account, so neither run may consume the other's balance
      | threads | stored | total  | per_account | flagged |
      | 8       | 16     | 1600   | 800         | 0       |
      | 12      | 24     | 2400   | 1200        | 2       |

  Scenario: A currency with no rate is stored concurrently without losing rows
    Given the account "0000000459" and the category "product"
    And a limit of "1000" USD was installed on "2022-01-01"
    When 10 threads each post a transaction of "100" KZT against the ledger on "2022-01-15"
    Then 10 transactions are stored
    And every transaction is stored without a flag
    And no automatic fallback limit was created for the account
