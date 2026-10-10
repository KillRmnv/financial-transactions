Feature: The boundaries of the request payload

  Bean Validation runs only at the HTTP boundary, so the partitions below cannot be
  checked by calling the service directly: an annotation the service never sees
  proves nothing. That is why these are integration scenarios and not unit tests.

  Two techniques are used, and they answer different questions.

  **Equivalence partitioning** groups inputs that should behave the same and picks
  one representative of each. A wrong rule usually breaks a whole class at once, so
  one scenario per class is enough to catch it.

  **Boundary value analysis** sits on the edge of a partition, just inside, just
  outside, and exactly on it. Off-by-one errors live there and nowhere else — an
  inclusive compared with an exclusive comparison, a digit count of 10 against 11, a
  scale of 2 against 3. Those are invisible to equivalence partitioning precisely
  because both sides of the boundary are represented and both happen to work.

  Amounts in the tables are quoted on purpose: a step taking {string} only matches a
  quoted cell, and Gherkin passes the quotes through as part of the value. An
  unquoted cell reaches the step bare and matches nothing.

  Background:
    Given the account "0000000123" and the category "product"

  Scenario Outline: The sum, by boundary and by equivalence class
    When a transaction of <sum> USD happened on "2022-01-05"
    Then the response is <status>

    Examples: Boundaries of @DecimalMin(0.00, inclusive=false), then of the column the row lands in
      | sum               | status | why                                        |
      | "0.00"            | 400    | the minimum, excluded by inclusive=false   |
      | "0"               | 400    | zero, written without decimals             |
      | "0.01"            | 201    | the smallest sum the API accepts           |
      | "0.001"           | 201    | three fraction digits, which is a scale a low-rate currency needs |
      | "0.00000001"      | 201    | eight fraction digits, the storage scale   |
      | "0.000000001"     | 400    | nine fraction digits, one past the column  |
      | "100.00"          | 201    | an ordinary amount                         |
      | "999999999999.99" | 201    | 12 integer digits, the storage maximum     |
      | "1000000000000.00" | 400   | 13 integer digits, one past the column     |
      | "-0.01"           | 400    | negative                                   |
      | "-1000.00"        | 400    | a large negative                           |

  Scenario Outline: The sum of a limit follows the same boundaries
    When a limit of <sum> USD is requested on "2022-01-01"
    Then the response is <status>

    Examples:
      | sum               | status |
      | "0.00"            | 400    |
      | "0.01"            | 201    |
      | "0.001"           | 201    |
      | "999999999999.99" | 201    |
      | "1000000000000.00" | 400   |
      | "-0.01"           | 400    |

  Scenario Outline: The account is exactly ten digits
    When a transaction from account "<account>" of "10" USD happened on "2022-01-05"
    Then the response is <status>

    Examples: Boundaries on the length, then the invalid classes within it
      | account    | status | why                                    |
      | 000000001  | 400    | 9 digits, one too few                 |
      | 0000000123 | 201    | exactly ten                            |
      | 00000001234 | 400   | 11 digits, one too many               |
      | abcdefghij | 400    | the right length, the wrong characters |
      | 00000-123  | 400    | a dash where a digit belongs          |
      | 00000 123  | 400    | an interior space                     |
      | +000000123 | 400    | a plus sign where a digit belongs     |

  Scenario Outline: The currency shortname is exactly three letters
    When a transaction in "<currency>" for "10" USD happened on "2022-01-05"
    Then the response is <status>

    Examples: Boundaries on the length
      | currency | status |
      | US       | 400    |
      | USD      | 201    |
      | USDD     | 400    |

    Examples: The classes that share a length of three
      | currency | status |
      | KZT      | 201    |
      | kzt      | 201    |
      | KZT1     | 400    |
      | U S      | 400    |
      | 123      | 400    |

  Scenario Outline: The category is one of the two the lookup holds
    When a transaction in the category "<category>" for "10" USD happened on "2022-01-05"
    Then the response is <status>

    Examples:
      | category | status |
      | product  | 201    |
      | service  | 201    |
      | gold     | 404    |

  Scenario Outline: The timestamp must be after 1990, and the comparison is strict
    When a transaction dated "<moment>" of "10" USD happened
    Then the response is <status>

    Examples: Boundaries of ck_expense_transaction_occurred_at_sane
      | moment               | status | why                                          |
      | 1989-12-31T23:59:59Z | 400    | one second before the boundary               |
      | 1990-01-01T00:00:00Z | 400    | exactly on it, excluded by the strict ">"    |
      | 1990-01-01T00:00:01Z | 201    | one second past it, the smallest that works |
      | 1990-01-02T00:00:00Z | 201    | a whole day past                             |
      | 2022-01-05T00:00:00Z | 201    | an ordinary moment                           |
      | 2022-01-05T00:00:00+06:00 | 201 | the same instant, written with an offset     |

  Scenario Outline: A required field that is missing or blank
    When a transaction is posted with the body:
      """
      {
        "accountFrom": <accountFrom>,
        "accountTo": "9999999999",
        "currencyShortname": "USD",
        "sum": 10.00,
        "expenseCategory": "product",
        "datetime": "2022-01-05T00:00:00+00:00"
      }
      """
    Then the response is 400
    And the response is a problem of type "validation-failed"
    And the violation names the field "accountFrom"

    Examples:
      | accountFrom  |
      | null         |
      | ""           |
      | "   "        |

  Scenario: A body carrying only one field of the six
    When a transaction is posted with the body:
      """
      {"accountFrom": "0000000123"}
      """
    Then the response is 400
    And the response is a problem of type "validation-failed"
    And the violation names the field "accountTo"

  Scenario: A body that is not JSON at all
    When a transaction is posted with the body:
      """
      this is not json
      """
    Then the response is 400
    And the response is a problem of type "malformed-request"

  Scenario: The body has to declare that it is JSON
    When a transaction is posted with an untyped body:
      """
      {"accountFrom": "0000000123", "accountTo": "9999999999", "currencyShortname": "USD", "sum": 10.00, "expenseCategory": "product", "datetime": "2022-01-05T00:00:00+00:00"}
      """
    Then the response is 415

  Scenario: A valid boundary value is stored, not merely accepted
    When a transaction of "999999999999.99" USD happened on "2022-01-05"
    Then the response is 201
    And 1 transactions are stored
    And the transaction of "2022-01-05" totals "999999999999.99" USD