Feature: The client-facing API over HTTP

  The specification requires one end-to-end API test that reproduces "case 1"
  through HTTP. This is it: the whole API is driven over a real port against
  PostgreSQL, with the external rates API replaced by a stub bean.

  Each returned row carries the limit the flag was actually computed against,
  which is not necessarily the limit installed today.

  Background:
    Given the account "0000000123" and the category "product"

  Scenario: Case 1 over HTTP — only 3 and 13 January come back, with the limit that was exceeded
    Given a limit of "1000" USD was installed on "2022-01-01"
    And a transaction of "500" USD happened on "2022-01-02"
    And a transaction of "600" USD happened on "2022-01-03"
    And a limit of "2000" USD was installed on "2022-01-10"
    And a transaction of "800" USD happened on "2022-01-11"
    And a transaction of "100" USD happened on "2022-01-12"
    And a transaction of "100" USD happened on "2022-01-13"
    And a transaction of "100" USD happened on "2022-01-13"
    When the exceeded transactions for "2022-01" are requested
    Then the response is 200
    And the response holds 3 exceeded transactions
    And the exceeded transactions are on these dates:
      | date       |
      | 2022-01-03 |
      | 2022-01-13 |
      | 2022-01-13 |
    And the 1st exceeded transaction reports a limit of "1000" USD installed on "2022-01-01" in "USD"
    And the 2nd exceeded transaction reports a limit of "2000" USD installed on "2022-01-10" in "USD"

  Scenario: A month with no exceeded transactions returns an empty list
    Given a limit of "1000" USD was installed on "2022-01-01"
    And a transaction of "100" USD happened on "2022-01-02"
    When the exceeded transactions for "2022-01" are requested
    Then the response is 200
    And the response holds 0 exceeded transactions

  Scenario: An invalid body is answered with a ProblemDetail
    When a transaction is posted with an invalid body
    Then the response is 400
    And the response is a problem of type "validation-failed"
    And the response lists the field violations

  Scenario: An unsupported currency is refused, and the accepted set is published
    When a transaction in "ZZZ" for "10.00" USD happened on "2022-01-02"
    Then the response is 404
    And the response is a problem of type "not-found"
    When the accepted currencies are requested
    Then the response is 200
    And the accepted currencies include "KZT", "RUB" and "USD"
    And the accepted currencies do not include "kzt"

  Scenario: An unknown category is answered as a ProblemDetail 404
    When a transaction in the category "gold" for "10.00" USD happened on "2022-01-02"
    Then the response is 404
    And the response is a problem of type "not-found"