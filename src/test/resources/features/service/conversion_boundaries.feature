Feature: The boundaries of converting into the ledger currency

  A conversion multiplies an amount by a rate and rounds once. Both halves have
  boundaries, and both are places where an implementation can be wrong in a way no
  ordinary amount would reveal.

  **Rounding.** `LedgerPolicy.round` is HALF_UP at the configured scale, so the
  interesting inputs are the half-way ones. HALF_UP and HALF_DOWN differ only on an
  exact .5, which is precisely the value a "typical" amount never produces and a
  boundary test always does.

  **Precision.** The rate is not rounded before the multiplication. Rounding first
  destroys the only precision the conversion has, and the test that catches it needs an
  amount large enough for the difference to survive to two decimals.

  **Equivalence classes of a rate.** A rate that is not positive is unusable: it would
  convert an amount into a negative ledger figure, and a zero rate would silently
  report every transaction as free. The lookup discards those rather than applying them.

  **The rate is looked up for the first day of the month**, not for the day the
  transaction happened, because `TransactionService` passes `occurredMonth` to the
  conversion. So a rate seeded for the 3rd of January is not found by a transaction of
  the 5th, while one seeded for the 1st is. Every scenario below dates its rate
  accordingly, and two of them exist to pin that behaviour down.

  Background:
    Given the account "0000000123" and the category "product"
    And a limit of "1000" USD was installed on "2022-01-01"

  Scenario Outline: Rounding is HALF_UP, so an exact half goes up
    Given the rate for "KZT" on "2022-01-01" is <rate>
    When a transaction of <sum> KZT was posted on "2022-01-05"
    Then the transaction of "2022-01-05" totals "<expected>" USD

    Examples: Half-way and just either side of it
      | rate   | sum   | expected | why                                         |
      | 0.5    | "0.05" | 0.03     | 0.025 rounds up, the HALF_UP decision       |
      | 0.5    | "0.04" | 0.02     | 0.020 lands exactly, no rounding needed     |
      | 0.5    | "0.03" | 0.02     | 0.015 rounds up                             |
      | 0.5    | "0.02" | 0.01     | 0.010 lands exactly                          |
      | 0.5    | "0.01" | 0.01     | 0.005 rounds up                             |
      | 1.005  | "1"    | 1.01     | the rate itself is half-way                 |
      | 0.0001 | "0.50" | 0.00     | below half a cent, rounds to nothing        |

  Scenario: The rate is not rounded before the amount is multiplied
    Given the rate for "KZT" on "2022-01-01" is 1.00000001
    When a transaction of "999.99" KZT was posted on "2022-01-05"
    Then the transaction of "2022-01-05" totals "999.99" USD

  Scenario Outline: A rate that is not positive cannot convert anything
    Given the rate for "KZT" on "2022-01-01" is <rate>
    When a transaction of "100" KZT was posted on "2022-01-05"
    Then the transaction of "2022-01-05" is stored without a flag

    Examples:
      | rate | why                       |
      | 0    | a zero rate makes it free  |
      | -1   | a negative rate is no rate |

  Scenario: The smallest positive rate is still a rate
    Given the rate for "KZT" on "2022-01-01" is 0.00000001
    When a transaction of "100000000" KZT was posted on "2022-01-05"
    Then the transaction of "2022-01-05" totals "1.00" USD

  Scenario: The rate of the month's first day is used as it is
    Given the rate for "KZT" on "2022-01-01" is 0.5
    When a transaction of "100" KZT was posted on "2022-01-05"
    Then the transaction of "2022-01-05" totals "50.00" USD
    And the rate used for "2022-01-05" is 0.5

  Scenario: A later day of the same month is not the rate of this month
    Given the rate for "KZT" on "2022-01-03" is 0.4
    When a transaction of "100" KZT was posted on "2022-01-05"
    Then the transaction of "2022-01-05" is stored without a flag

  Scenario: A month with no rate of its own falls back to the most recent earlier one
    Given the rate for "KZT" on "2021-12-31" is 0.4
    When a transaction of "100" KZT was posted on "2022-01-05"
    Then the transaction of "2022-01-05" totals "40.00" USD
    And the rate used for "2022-01-05" is 0.4

  Scenario: A currency with no rate anywhere is stored rather than dropped
    When a transaction of "100" KZT was posted on "2022-01-05"
    Then 1 transactions are stored
    And the transaction of "2022-01-05" is stored without a flag

  Scenario: A provider that is down does not cost the transaction
    Given the rates provider fails
    When a transaction of "100" KZT was posted on "2022-01-05"
    Then 1 transactions are stored
    And the transaction of "2022-01-05" is stored without a flag

  Scenario: A stored rate is found without asking the provider at all
    Given the rate for "KZT" on "2022-01-01" is 0.5
    And the rates provider fails
    When a transaction of "100" KZT was posted on "2022-01-05"
    Then the transaction of "2022-01-05" totals "50.00" USD

  Scenario: The ledger currency needs no rate at all
    When a transaction of "100.005" USD happened on "2022-01-05"
    Then the transaction of "2022-01-05" totals "100.01" USD