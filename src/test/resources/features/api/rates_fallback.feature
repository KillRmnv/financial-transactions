Feature: A rate the paid provider does not have

  The paid provider's free tier reaches back a year and leaves weekends empty, so a
  transaction older than that or dated on a Saturday has no answer from it. An
  ingestion path that stopped there would store a transaction it could have priced,
  with its flag left uncalculated for ever. Frankfurter answers those: no key, no
  quota, and a rate for every calendar day.

  The rate that answers carries its own provider, because `currency_rate.source`
  exists to name the one that supplied the number — not the one that was asked
  first.

  Rates below are quoted the way this provider answers them: `KZT/USD` is dollars
  per tenge, so a value near 0.0022 and not near 450. Quoting it the other way round
  would inflate a transaction a thousandfold and fail on the flag rather than on the
  provider.

  Scenario: The fallback answers when the paid provider has nothing
    Given the paid provider has no rate for "KZT" against "USD" on "2022-01-08"
    And the fallback answers "KZT" against "USD" on "2022-01-08" at "0.00222"
    When a rate is resolved for "KZT" against "USD" on "2022-01-08"
    Then the resolved rate is "0.00222"
    And the stored rate names "frankfurter" as its source

  Scenario: The paid provider is asked first
    Given the paid provider answers "KZT" against "USD" on "2022-01-08" at "450.00"
    And the fallback answers "KZT" against "USD" on "2022-01-08" at "0.00222"
    When a rate is resolved for "KZT" against "USD" on "2022-01-08"
    Then the resolved rate is "450.00"
    And the stored rate names "wiremock" as its source

  Scenario: The fallback is asked for the pair in the orientation asked for
    Given the paid provider has no rate for "KZT" against "USD" on "2022-01-08"
    And the fallback answers "KZT" against "USD" on "2022-01-08" at "0.00222"
    When a rate is resolved for "KZT" against "USD" on "2022-01-08"
    Then the fallback was asked for "kzt/usd"

  # The case the paid provider cannot serve at all: a Saturday. It is the reason
  # previous_close exists there, and the reason this provider is worth having.
  Scenario: A Saturday has a rate, and no previous close to fall back on
    Given the paid provider has no rate for "KZT" against "USD" on "2022-01-08"
    And the fallback answers "KZT" against "USD" on "2022-01-08" at "0.00222"
    When a rate is resolved for "KZT" against "USD" on "2022-01-08"
    Then the resolved rate is "0.00222"
    And the resolved rate has no previous close, because this provider has no gap to fill

  Scenario: Neither provider has it, and that is not an error
    Given the paid provider has no rate for "KZT" against "USD" on "2022-01-08"
    And the fallback has no rate for "KZT" against "USD" on "2022-01-08"
    When a rate is sought for "KZT" against "USD" on "2022-01-08"
    Then no rate was resolved
    And nothing was stored

  Scenario: A transaction older than the free tier is still priced
    Given the account "1234567890" and the category "product"
    And the paid provider has no rate for "KZT" against "USD" on "2022-01-01"
    And the fallback answers "KZT" against "USD" on "2022-01-01" at "0.0023"
    And a limit of "1000" USD was installed on "2022-01-01"
    When a transaction in "KZT" for "500.00" USD happened on "2022-01-03"
    Then the response is 201
    And 1 transactions are stored
    And the transaction of "2022-01-03" is not flagged

  Scenario: The currency list is the paid provider's when it has one
    Given the fallback publishes these currencies, including retired ones:
      | code | name                 | retired |
      | USD  | United States Dollar | no      |
      | KZT  | Kazakhstani Tenge    | no      |
      | DEM  | German Mark          | yes     |
    Then the published currencies are "EUR" and "GBP"

  Scenario: The fallback's list is used, with no retired codes in it, when the paid one has none
    Given the paid provider publishes no currencies
    And the fallback publishes these currencies, including retired ones:
      | code | name                 | retired |
      | USD  | United States Dollar | no      |
      | KZT  | Kazakhstani Tenge    | no      |
      | RUB  | Russian Ruble        | no      |
      | DEM  | German Mark          | yes     |
      | RUR  | Russian Ruble (1991) | yes     |
      | TRL  | Turkish Lira (1922)  | yes     |
    Then the published currencies are "USD", "KZT" and "RUB"