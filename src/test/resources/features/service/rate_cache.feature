Feature: Rates are cached in Redis in front of the database

  The specification's optional point 2* asks for the rates to be cached in Redis over
  the main database. The cache is not the point — the database already answers — so
  what these scenarios check is the things that make a cache safe rather than merely
  fast.

  **It is a cache, not a second source of truth.** A value that cannot be read has to
  cost one lookup rather than a wrong conversion, because the table is right there.

  **A refresh is visible at once.** A cached answer outlives the row it came from until
  something says otherwise, and a cache that retires only the key it happens to know
  about is wrong here in a way that looks right: the rate is stored under the first day
  of the month, so the entry a transaction of the 5th reads and the entry a transaction
  of the 6th reads are the same one, and it has to go when the rate changes.

  The refresh goes through the service rather than through SQL, because that is where
  the invalidation lives. A scenario that updated the row directly would be testing a
  cache that could never notice a change — which is a real way to build this, and the
  reason the step is named "is refreshed to" rather than "becomes".

  **A miss is not cached.** Caching "there is no rate for this day" would keep sending
  transactions down the paid external path after the sweep has published one, which is
  the opposite of what the cache is for.

  Switching the cache off entirely is not here, because a scenario cannot flip a
  property: `RateCacheDisabledTests` starts a context with `app.cache.enabled=false`
  and asserts the same conversion still happens, out of the database alone.

  Background:
    Given the account "0000000123" and the category "product"
    And a limit of "1000" USD was installed on "2022-01-01"

  Scenario: A second conversion is answered without reading the table again
    Given the rate for "KZT" on "2022-01-01" is 0.0025
    When a transaction of "400" KZT was posted on "2022-01-05"
    And a transaction of "400" KZT was posted on "2022-01-06"
    Then the transaction of "2022-01-05" totals "1.00" USD
    And the transaction of "2022-01-06" totals "1.00" USD
    And the rate was resolved from the cache at least once

  Scenario: A refreshed rate is visible on the next lookup, not at the end of the TTL
    Given the rate for "KZT" on "2022-01-01" is 0.0025
    When a transaction of "400" KZT was posted on "2022-01-05"
    And the rate for "KZT" on "2022-01-01" is refreshed to 0.0050
    And a transaction of "400" KZT was posted on "2022-01-06"
    Then the transaction of "2022-01-06" totals "2.00" USD

  Scenario: A refresh is visible for the day that was already converted too
    Given the rate for "KZT" on "2022-01-01" is 0.0025
    When a transaction of "400" KZT was posted on "2022-01-05"
    And the rate for "KZT" on "2022-01-01" is refreshed to 0.0050
    And a transaction of "800" KZT was posted on "2022-01-07"
    Then the transaction of "2022-01-05" totals "1.00" USD
    And the transaction of "2022-01-07" totals "4.00" USD

  Scenario: An unreadable cached rate is dropped rather than believed
    Given the rate for "KZT" on "2022-01-01" is 0.0025
    And a transaction of "400" KZT was posted on "2022-01-05"
    And the cached "KZT" rate is replaced with something that is not a rate
    When a transaction of "400" KZT was posted on "2022-01-06"
    Then the transaction of "2022-01-05" totals "1.00" USD
    And the transaction of "2022-01-06" totals "1.00" USD