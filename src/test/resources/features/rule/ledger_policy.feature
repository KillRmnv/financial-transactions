Feature: What the ledger configuration refuses

  `LedgerPolicy` is bound from configuration at startup, and it validates its own
  binding. A ledger silently denominated in nothing is the failure that validation
  exists to prevent: it would not throw anywhere later, it would simply find no
  currency and leave every amount unconverted.

  These are boundary cases on two fields, and they need no Spring and no database —
  the record is a plain constructor call, so a bad value is refused in milliseconds
  rather than at the next context startup.

  Scenario Outline: The ledger currency is three uppercase letters
    When the ledger is configured with currency "<code>"
    Then the configuration is <outcome>

    Examples: Boundaries on the length, then the classes within it
      | code | outcome  | why                                          |
      | USD  | accepted | the ordinary case                            |
      | EUR  | accepted | moving the ledger is a property, not a rewrite |
      | US   | refused  | two letters                                  |
      | USDD | refused  | four letters                                 |
      | usd  | refused  | lowercase, which would match nothing          |
      | 123  | refused  | digits, which are not an ISO 4217 code        |

  # An empty or absent value cannot go in an Examples cell: Gherkin passes a quoted
  # cell through with its quotes intact, so an empty one arrives as two quote marks
  # rather than nothing. It gets a step of its own instead.
  Scenario: A currency that is blank is refused
    When the ledger is configured with a blank currency
    Then the configuration is refused

  Scenario Outline: The amount scale cannot be negative
    When the ledger is configured with a scale of <scale>
    Then the configuration is <outcome>

    Examples:
      | scale | outcome  |
      | -1    | refused  |
      | -100  | refused  |
      | 0     | accepted |
      | 2     | accepted |
      | 4     | accepted |

  Scenario: Rounding is half-up at the configured scale
    Given the ledger is configured with currency "USD" and a scale of 2
    Then 0.025 rounds to "0.03"
    And 0.024 rounds to "0.02"
    And -0.025 rounds to "-0.03"
    And 2.675 rounds to "2.68"

  Scenario: A different scale is honoured rather than assumed
    Given the ledger is configured with currency "USD" and a scale of 0
    Then 0.4 rounds to "0"
    And 0.5 rounds to "1"

  Scenario: The ledger currency needs no rate of its own
    Given the ledger is configured with currency "EUR" and a scale of 2
    Then "EUR" is a ledger currency
    And "USD" is not a ledger currency