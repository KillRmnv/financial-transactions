Feature: Where a timestamp stops belonging to one month

  A transaction arrives with an offset and belongs to a month for as long as one
  timezone is used to decide it. `domain/MonthBoundary` is the only place that
  decision is made, and it is a pure function, so these boundaries cost milliseconds
  and need no database.

  The two offset scenarios are the point of the feature. Both timestamps look like
  they belong to a different month from the one they are filed under, because a
  reader naturally compares them against their own wall clock:

  - 23:00 on 31 January at -06:00 is already 05:00 on 1 February in UTC.
  - 01:00 on 1 February at +06:00 is still 19:00 on 31 January in UTC.

  Filing either of them by the offset the sender happened to use would put a
  transaction in the wrong monthly total, and only an amount near the limit would
  make that visible. That is why the rule lives in one place and why these cases
  exist.

  Background:
    Given the accounting timezone is "UTC"

  Scenario Outline: The last and first instants of a month
    When a transaction happens at "<moment>"
    Then it belongs to the month "<month>"

    Examples: Boundaries of the month, in milliseconds
      | moment                    | month       |
      | 2022-01-31T23:59:59.999Z  | 2022-01-01  |
      | 2022-02-01T00:00:00Z      | 2022-02-01  |
      | 2022-01-01T00:00:00Z      | 2022-01-01  |
      | 2021-12-31T23:59:59.999Z  | 2021-12-01  |
      | 2022-03-31T00:00:00Z      | 2022-03-01  |

  Scenario Outline: The offset the sender used does not decide the month
    When a transaction happens at "<moment>"
    Then it belongs to the month "<month>"
    And the instant in UTC is "<inUtc>"

    Examples: Two timestamps whose local date and UTC date disagree
      | moment                   | inUtc                   | month      |
      | 2022-01-31T23:00:00-06:00 | 2022-02-01T05:00:00Z  | 2022-02-01 |
      | 2022-02-01T01:00:00+06:00 | 2022-01-31T19:00:00Z  | 2022-01-01 |
      | 2022-03-01T00:30:00+06:00 | 2022-02-28T18:30:00Z  | 2022-02-01 |
      | 2022-02-28T23:30:00-06:00 | 2022-03-01T05:30:00Z  | 2022-03-01 |

  Scenario Outline: A year boundary behaves like a month boundary
    When a transaction happens at "<moment>"
    Then it belongs to the month "<month>"

    Examples:
      | moment                   | month       |
      | 2021-12-31T23:59:59.999Z | 2021-12-01  |
      | 2022-01-01T00:00:00Z     | 2022-01-01  |

  Scenario: A leap day belongs to February and the month after it does not
    When a transaction happens at "2024-02-29T12:00:00Z"
    Then it belongs to the month "2024-02-01"
    When a transaction happens at "2024-03-01T12:00:00Z"
    Then it belongs to the month "2024-03-01"