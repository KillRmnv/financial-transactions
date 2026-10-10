Feature: The weekly sweep of the currency lookup

  `WeeklyCurrencyImport` is the only writer of the currency lookup apart from the
  migration. It runs on a Monday cron and adds whatever the provider publishes and
  the lookup does not have yet.

  It is worth covering rather than deleting for one reason, and the last scenario is
  that reason: a currency reaches the lookup **only** here. A transaction in a code
  the lookup lacks is refused with 404 by `TransactionService.currency`, and the
  on-demand rate fetch does not help, because `currency_rate.base_currency` is a
  foreign key onto `currency(code)` — a rate for a currency that is not in the
  lookup has no row to point at. Removing the sweep would mean that a currency
  appearing after the seed stays unaccepted until the next release.

  The sweep is invoked directly rather than by waiting for the cron. The schedule is
  configuration, and a scenario that slept until Monday 04:30 would make the suite
  either slow or dependent on the hour it runs.

  Background:
    Given the account "0000000123" and the category "product"

  Scenario: The sweep adds a code the lookup does not have
    Given the provider publishes these currencies:
      | code | name            |
      | ZWL  | Zimbabwe Dollar |
    When the weekly sweep runs
    Then the currency lookup grew by 1 codes
    And the currency "ZWL" is in the lookup

  Scenario: A code the lookup already has is not added twice
    Given the provider publishes these currencies:
      | code | name                |
      | USD  | US Dollar           |
      | KZT  | Kazakhstani Tenge   |
    When the weekly sweep runs
    Then the currency lookup grew by 0 codes
    And the currency "ZWL" is not in the lookup

  Scenario Outline: A code that is not three uppercase letters is skipped
    Given the provider publishes these currencies:
      | code   | name      |
      | <code> | something |
    When the weekly sweep runs
    Then the currency lookup grew by 0 codes
    And the currency lookup still holds the same number of codes

    Examples: Boundaries on the length, then the classes within it
      | code | why                                        |
      | US   | two letters                                |
      | USDD | four letters                               |
      | usd  | lowercase, which the lookup never holds    |
      | kzt  | lowercase of an existing code              |
      | 12   | digits, which are not an ISO 4217 code     |

  Scenario: Surrounding whitespace does not stop a code being added
    Given the provider publishes the currency " ZWL " as "A padded name"
    When the weekly sweep runs
    Then the currency lookup grew by 1 codes
    And the currency "ZWL" is in the lookup

  Scenario: A provider that cannot list leaves the lookup alone
    Given the provider cannot list its currencies
    When the weekly sweep runs
    Then the currency lookup grew by 0 codes
    And the currency "ZWL" is not in the lookup

  Scenario: A currency the sweep added is then accepted in a transaction
    Given the provider publishes these currencies:
      | code | name            |
      | ZWL  | Zimbabwe Dollar |
    And a limit of "1000" USD was installed on "2022-01-01"
    When the weekly sweep runs
    And a transaction of "100" ZWL was posted on "2022-01-05"
    Then 1 transactions are stored
    And the stored transaction is in currency "ZWL"

  Scenario: A currency the sweep has not added is still refused
    Given the provider publishes these currencies:
      | code | name |
      | EUR  | Euro |
    And a limit of "1000" USD was installed on "2022-01-01"
    When the weekly sweep runs
    Then the currency "ZWL" is not in the lookup
    And a transaction in "ZWL" for "100" USD is refused as not found

  # The switch lives in scheduled_job rather than in a property, because a bean condition is
  # settled when the context starts. These scenarios are the only proof that it still costs
  # nothing to be off: no restart, and the provider is not called at all.
  Scenario: A sweep switched off does not call the provider
    Given the provider publishes these currencies:
      | code | name |
      | ZWL  | Zimbabwean Dollar |
    And the weekly currency import is switched off
    When the weekly sweep runs
    Then the provider was not asked for its currency list
    And the currency lookup grew by 0 codes
    And the currency "ZWL" is not in the lookup

  Scenario: Switching the sweep back on needs no restart
    Given the provider publishes these currencies:
      | code | name |
      | ZWL  | Zimbabwean Dollar |
    And the weekly currency import is switched off
    When the weekly sweep runs
    Then the provider was not asked for its currency list
    When the weekly currency import is switched on
    And the weekly sweep runs
    Then the provider was asked for its currency list
    And the currency lookup grew by 1 codes

  Scenario: The switch survives being set to the value it already has
    Given the provider publishes these currencies:
      | code | name |
      | ZWL  | Zimbabwean Dollar |
    And the weekly currency import is switched on
    When the weekly sweep runs
    Then the provider was asked for its currency list
    And the currency lookup grew by 1 codes