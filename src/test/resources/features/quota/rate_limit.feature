Feature: Request quotas

  A quota that is never exhausted is a quota that is never verified, so these
  scenarios spend the real quota and check both halves of the contract: the
  requests inside it succeed, and the next one is refused with a ProblemDetail
  and a Retry-After header.

  The numbers are overridden to three permits per hour so the boundary is
  deterministic. The production quota of 60 requests a second cannot be exhausted
  without making the test slow and flaky.

  Background:
    Given the ingestion quota is 3 requests per hour

  Scenario: The request past the quota is refused
    When 3 transactions are posted for account "0000000456"
    Then every one of them is answered with 201
    When another transaction is posted for account "0000000456"
    Then the response is 429
    And the response carries a Retry-After header
    And the response is a problem of type "rate-limited"
    And the "ingestion" quota refused at least 1 requests

  Scenario: The quota is per calling address, not per account
    When 3 transactions are posted for account "0000000456"
    And another transaction is posted for account "0000000999"
    Then the response is 429

  Scenario: An endpoint without a quota is never limited
    When the accepted currencies are requested 9 times
    Then every one of them is answered with 200