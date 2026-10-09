Feature: The monthly limit flag against a real database

  "Case 1" of the specification's truth table, replayed through the services
  against PostgreSQL. Limits are installed on 1 and 10 January, transactions
  arrive on 2, 3, 11, 12 and twice on 13 January, and only the transactions of
  3 and 13 January carry the flag.

  Time is driven by SettableClock so the installation dates land exactly where
  the specification puts them, and the exchange rate provider is stubbed: the
  scenario runs in USD, where the rate is 1.

  Background:
    Given the account "0000000123" and the category "product"

  Scenario: Case 1 — only the transactions of 3 and 13 January are flagged
    Given a limit of "1000" USD was installed on "2022-01-01"
    And a transaction of "500" USD happened on "2022-01-02"
    And a transaction of "600" USD happened on "2022-01-03"
    And a limit of "2000" USD was installed on "2022-01-10"
    And a transaction of "800" USD happened on "2022-01-11"
    And a transaction of "100" USD happened on "2022-01-12"
    And a transaction of "100" USD happened on "2022-01-13"
    And a transaction of "100" USD happened on "2022-01-13"
    Then the flagged transactions are exactly:
      | date       |
      | 2022-01-03 |
      | 2022-01-13 |
      | 2022-01-13 |

  Scenario: A remaining balance of exactly zero is not exceeded
    Given a limit of "1000" USD was installed on "2022-01-01"
    And a transaction of "1000" USD happened on "2022-01-02"
    Then 1 transactions are stored
    And the transaction of "2022-01-02" is not flagged
    And the flag for "2022-01-02" was calculated
    And the transaction of "2022-01-02" totals "1000.00" USD

  Scenario: Each transaction remembers the limit its flag was computed against
    Given a limit of "1000" USD was installed on "2022-01-01"
    And a transaction of "1100" USD happened on "2022-01-03"
    And a limit of "2000" USD was installed on "2022-01-10"
    And a transaction of "100" USD happened on "2022-01-11"
    Then the transaction of "2022-01-03" is flagged
    And the transaction of "2022-01-11" is not flagged
    And the transaction of "2022-01-03" was measured against a limit of "1000" USD
    And the transaction of "2022-01-11" was measured against a limit of "2000" USD

  Scenario: A new month starts a fresh running total
    Given a limit of "1000" USD was installed on "2022-01-01"
    And a transaction of "600" USD happened on "2022-01-31"
    And a transaction of "600" USD happened on "2022-02-01"
    Then the transaction of "2022-02-01" is not flagged

  Scenario: Backdated transactions are measured against the standing fallback
    Given the clock reads "2026-06-01T00:00:00Z"
    And a transaction of "500" USD happened on "2022-01-02"
    Then the transaction of "2022-01-02" is not flagged
    And the transaction of "2022-01-02" was measured against a limit of "1000" USD
    When a transaction of "600" USD happened on "2022-01-03"
    Then the transaction of "2022-01-03" is flagged

  Scenario: The automatic fallback is dated at the epoch and reports no installation date
    Given the clock reads "2026-06-01T00:00:00Z"
    And a transaction of "500" USD happened on "2022-01-02"
    Then the automatic default limit is "1000" USD
    And the automatic default limit was installed at the epoch

  Scenario Outline: Concurrent posts for one client serialise on the limit row
    Given a limit of "1000" USD was installed on "2022-01-01"
    When <threads> threads each post a transaction of "100" USD on "2022-01-15"
    Then <threads> transactions are stored
    And the month's total is "1200" USD
    And exactly 2 transactions are flagged
    And no transaction is stored without a flag

    Examples:
      | threads |
      | 12      |