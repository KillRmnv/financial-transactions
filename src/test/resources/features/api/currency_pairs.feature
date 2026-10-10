Feature: Which currencies the daily sweep keeps warm

  `currency_pair` decides what the daily sweep pays for: one request a day per active
  currency. Until now the only way to change it was SQL against the database, which is a
  different tool with a different set of permissions; this is the application route to
  the same rows.

  **Nothing here is a rejection.** A currency that is not tracked, or is tracked but
  inactive, is still accepted in a transaction and its rate is fetched on demand. The
  table only says what has already been pre-fetched, and the scenarios below are as
  much about that as about the endpoint.

  Scenario: The tracked set starts as the two the specification names
    When the tracked currencies are listed
    Then the response is 200
    And the tracked currencies are:
      | code | active |
      | KZT  | true   |
      | RUB  | true   |

  Scenario: Being untracked is not a rejection
    Given the account "1234567890" and the category "product"
    When a transaction in "EUR" for "10.00" USD happened on "2022-01-05"
    Then the response is 201
    And 1 transactions are stored
    And the transaction of "2022-01-05" is stored without a flag

  Scenario: A currency that was not tracked can be added to the sweep
    When "EUR" is set to be tracked
    Then the response is 200
    And the tracked currency "EUR" is active

  Scenario: A tracked currency stops being swept
    And "KZT" is set to be tracked
    When "KZT" is set to be untracked
    Then the response is 200
    And the tracked currency "KZT" is not active

  Scenario: A currency can be brought back after being retired
    And "KZT" is set to be untracked
    When "KZT" is set to be tracked
    Then the response is 200
    And the tracked currency "KZT" is active

  Scenario: Setting the value it already has is a success, not a conflict
    And "KZT" is set to be tracked
    When "KZT" is set to be tracked
    Then the response is 200
    And the tracked currency "KZT" is active

  Scenario: An untracked currency is not even retired
    When "GBP" is set to be untracked
    Then the response is 404
    And the response is a problem of type "not-found"

  Scenario Outline: A code is resolved the way a transaction resolves one
    When "<code>" is set to be tracked
    Then the response is 200
    And the tracked currency "EUR" is active

    Examples:
      | code | why                        |
      | EUR  | as written                 |
      | eur  | lowercase, as in a request |

  # A padded code cannot go in an Examples cell: Gherkin passes a cell through verbatim, so the
  # quotes would become part of the value and it would be refused as malformed.
  Scenario: A code padded with spaces is still the same code
    When " EUR " is set to be tracked
    Then the response is 200
    And the tracked currency "EUR" is active

  Scenario Outline: A code that is not three letters is refused
    When "<code>" is set to be tracked
    Then the response is 400
    And the response is a problem of type "malformed-request"

    Examples:
      | code | why                       |
      | EU   | two letters               |
      | EURO | four letters              |
      | 12   | digits                    |

  Scenario: A currency outside the lookup cannot be tracked
    When "ZZZ" is set to be tracked
    Then the response is 404
    And the response is a problem of type "not-found"