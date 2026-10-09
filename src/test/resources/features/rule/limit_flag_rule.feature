Feature: The limit_exceeded flag rule

  The rule judges one transaction against the month's running total and the limit
  in effect on the transaction date. It is a pure function, so every row of the
  specification's truth tables can be checked in milliseconds without a database.

  A remaining balance of exactly zero is NOT exceeded: the comparison is strict.
  A new limit does not reset what the month has already spent.

  Scenario Outline: The truth table, row by row
    Given the month has <spent> USD spent so far
    And a limit of <limit> USD is in effect
    When a transaction of <amount> USD is accepted
    Then the flag is <expected>

    Examples: Requirement 5 — the boundary cases
      | spent   | amount   | limit | expected |
      | 0       | 500      | 1000  | false    |
      | 500     | 600      | 1000  | true     |
      | 0       | 1000     | 1000  | false    |
      | 0       | 1000.01  | 1000  | true     |
      | 999.99  | 0.01     | 1000  | false    |
      | 999.99  | 0.02     | 1000  | true     |
      | 500     | 500      | 1000  | false    |
      | 500     | 500.01   | 1000  | true     |
      | 1000    | 0        | 1000  | false    |

  Scenario: Case 1 — the limit is raised mid-month without resetting the month
    Given the month has 1100 USD spent so far
    When the client installs a limit of 2000 USD
    Then the remaining balance is 900 USD
    When a transaction of 800 USD is accepted
    Then the flag is false
    When a transaction of 100 USD is accepted
    Then the flag is false
    When a transaction of 100 USD is accepted
    Then the flag is true

  Scenario: Case 2 — a limit lower than what has already been spent
    Given the month has 900 USD spent so far
    When the client installs a limit of 400 USD
    Then the remaining balance is -500 USD
    When a transaction of 300 USD is accepted
    Then the flag is true

  Scenario Outline: The limit in effect is the latest one installed no later than the transaction
    Given these limits were installed:
      | installedOn | amount |
      | 2022-01-09  | 1000   |
      | 2022-01-10  | 2000   |
    When a transaction happens on <transactionDate>
    Then the limit in effect is <effective>

    Examples:
      | transactionDate | effective |
      | 2022-01-08      | none      |
      | 2022-01-09      | 1000      |
      | 2022-01-10      | 2000      |
      | 2022-01-11      | 2000      |