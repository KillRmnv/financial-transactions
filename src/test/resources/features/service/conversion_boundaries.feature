Feature: The boundaries of converting into the ledger currency

  A conversion multiplies an amount by a rate and stores the product. Both halves have
  boundaries, and both are places where an implementation can be wrong in a way no
  ordinary amount would reveal.

  **The product is stored exactly.** The column holds eight decimal places, because a
  currency with a low rate converts an amount into a fraction of a unit: 10 KZT at 0.0022
  is 0.022, and 1 IDR at 0.000061 is 0.000061. Rounding the product to two decimals turned
  the first of those into 0.02 and the second into 0.00, and a month whose transactions all
  round to zero never moves the running total the limit is measured against. The rounding
  a client sees happens in the mappers, at the display scale, and
  `features/rule/ledger_policy.feature` is where that decision is pinned. A product that
  needs a ninth digit is rounded by the column itself — 999.99 × 1.00000001 lands as
  999.99001000 — which is why the table below quotes stored values to eight places.

  **Equivalence classes of a rate.** A rate that is not positive is unusable: it would
  convert an amount into a negative ledger figure, and a zero rate would silently
  report every transaction as free. The lookup discards those rather than applying them.

  **The rate is looked up for the day the transaction happened**, not for the first
  day of its month: `TransactionService` passes `MonthBoundary.dayOf(occurredAt)` to
  the conversion. A rate seeded for the 5th is therefore the one a transaction of the
  5th uses, and one seeded for the 7th is not used at all — a price published later
  cannot price an earlier day. A rate seeded for the 1st still answers for the 5th,
  but only through the fallback for a day with no rate of its own. Two scenarios below
  exist to pin the ordering down.

  Background:
    Given the account "0000000123" and the category "product"
    And a limit of "1000" USD was installed on "2022-01-01"

  Scenario Outline: What is stored is the product, not the product rounded
    Given the rate for "KZT" on "2022-01-01" is <rate>
    When a transaction of <sum> KZT was posted on "2022-01-05"
    Then the transaction of "2022-01-05" totals "<stored>" USD

    Examples: Products that used to be rounded away on the way into the row
      | rate   | sum   | stored   | why                                        |
      | 0.5    | "0.05" | 0.025    | a half a cent, which is not zero           |
      | 0.5    | "0.04" | 0.02     | lands exactly                              |
      | 0.5    | "0.03" | 0.015    | a half a cent in the other direction       |
      | 0.5    | "0.02" | 0.01     | lands exactly                              |
      | 0.5    | "0.01" | 0.005    | half of a half a cent                      |
      | 1.005  | "1"    | 1.005    | the rate itself is half-way                |
      | 0.0001 | "0.50" | 0.00005  | below half a cent, which is not zero either |

  Scenario: The rate is not rounded before the amount is multiplied
    Given the rate for "KZT" on "2022-01-01" is 1.00000001
    When a transaction of "999.99" KZT was posted on "2022-01-05"
    Then the transaction of "2022-01-05" totals "999.99001000" USD

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

  Scenario: A product the ledger column cannot hold is refused, not written
    Given the rate for "KZT" on "2022-01-01" is 10.00000001
    # 99999999999.99999999 passes @Digits, and the product is past numeric(20, 8).
    # Without a named refusal this is an insert that fails, which a caller reads as
    # a constraint it does not know the name of, on a field that validated.
    Then a transaction of "99999999999.99999999" KZT at that rate is refused as too large

  Scenario: A rate of exactly ten keeps a sum at the boundary inside the column
    Given the rate for "KZT" on "2022-01-01" is 10
    When a transaction of "99999999999.99999999" KZT was posted on "2022-01-05"
    Then the transaction of "2022-01-05" totals "999999999999.99999990" USD

  Scenario: A currency whose rate is below one cannot overflow at any legal sum
    # The bound is on the product, not on the sum, so it has to hold in the other
    # direction too: a low-rate currency converts a sum at the ceiling downward.
    Given the rate for "KZT" on "2022-01-01" is 0.00000001
    When a transaction of "99999999999.99999999" KZT was posted on "2022-01-05"
    Then the transaction of "2022-01-05" totals "1000.00000000" USD

  Scenario: A rate from earlier in the month is used as it is
    Given the rate for "KZT" on "2022-01-01" is 0.5
    When a transaction of "100" KZT was posted on "2022-01-05"
    Then the transaction of "2022-01-05" totals "50.00" USD
    And the rate used for "2022-01-05" is 0.5

  # The specification asks for "the exchange rate on the day of the expense, or the last
  # closing rate". It used to be priced by the month's opening rate instead, which was a
  # defensible reading — one rate per month, so the currency cannot cross a limit from under
  # it — and was not what the document said.
  Scenario: The rate used is the one for the day of the expense
    Given the rate for "KZT" on "2022-01-05" is 0.4
    When a transaction of "100" KZT was posted on "2022-01-05"
    Then the transaction of "2022-01-05" totals "40.00" USD
    And the rate used for "2022-01-05" is 0.4

  # The other direction of the same rule, and the one it is easy to get wrong: a rate
  # published after the day must not price it, even though it sits in the same month and
  # is nearer on the calendar than anything earlier.
  Scenario: A rate published after the day does not price it
    Given the rate for "KZT" on "2022-01-07" is 0.4
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
    # The scenario above posts USD everywhere else, and a currency with no rate is the one path
    # that never runs. It is also the one an operator needs to see: a counter that folded it into
    # "within limit" would show a service quietly pricing nothing as a service pricing everything.
    And at least 1 transactions were accepted with the outcome "uncalculated"

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

  # No rate and no multiplication, so there is nothing to round: the amount arrives as
  # the client sent it. The 100.01 a client is shown is the display scale rounding it,
  # which is asserted in the API suite.
  Scenario: The ledger currency needs no rate at all
    When a transaction of "100.005" USD happened on "2022-01-05"
    Then the transaction of "2022-01-05" totals "100.005" USD