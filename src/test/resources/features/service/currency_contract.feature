Feature: The currency code is a verified reference

  A caller must not be able to introduce "kzt" alongside "KZT". Two things enforce
  that and both are checked here: the schema refuses a lowercase or malformed code
  outright, and the service resolves the code against the lookup rather than
  echoing it back as a new currency.

  Background:
    Given the account "0000000123" and the category "product"

  Scenario: The seeded lookup holds the currencies the specification names
    Then the currency lookup contains "KZT", "RUB" and "USD"
    And every code in the lookup is uppercase

  # The lookup used to be checked for holding "more than 150 codes" — an assertion that the ISO 4217
  # list had been pasted into the migration. It is now the provider's list plus what the scenarios
  # need, and what the code guarantees is that every one of them is a well-formed uppercase code.
  Scenario: Every code in the lookup came through a check the schema enforces
    Then every code in the lookup is three uppercase letters

  Scenario Outline: The schema refuses a code that is not three uppercase letters
    When a currency "<code>" is inserted directly
    Then the insert of "<code>" is refused by the schema

    Examples:
      | code |
      | kzt  |
      | KZT1 |
      | ru   |

  Scenario: A lowercase code in a request resolves to the seeded currency
    When a transaction in "kzt" for "10.00" USD happened on "2022-01-05"
    Then 1 transactions are stored
    And the stored transaction is in currency "KZT"
    And the currency lookup still holds the same number of codes

  Scenario: A currency outside the lookup is refused
    Then a transaction in "XXX" for "10.00" USD is refused as not found
    And the refusal names "XXX"

  Scenario: A rate cannot be stored for a currency that is not in the lookup
    When a rate is inserted directly for currency "ZZZ"
    Then the insert of "ZZZ" is refused by the schema

  Scenario: The ledger currency and its scale come from configuration, not from a constant
    Then the ledger is kept in "USD" with 2 decimal places
    When a limit of "1000" USD was installed on "2022-01-01"
    Then the installed limit is denominated in "USD"
    And a transaction of "250" USD happened on "2022-01-02"
    Then the transaction of "2022-01-02" totals "250.00" USD
    And the transaction of "2022-01-02" was measured against a limit of "1000" USD