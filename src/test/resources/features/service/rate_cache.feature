Feature: Rates are cached in Redis in front of the database

  The specification's optional point 2* asks for the rates to be cached in Redis over
  the main database. The cache is not the point — the database already answers — so
  what these scenarios check is the things that make a cache safe rather than merely
  fast.

  **It is a cache, not a second source of truth.** A value that cannot be read has to
  cost one lookup rather than a wrong conversion, because the table is right there.

  **A refresh is visible at once.** A cached answer outlives the row it came from until
  something says otherwise, and a cache that retires only the key it happens to know
  about is wrong here in a way that looks right. The key is the day that was **asked
  for**, not the day the answer came from: a transaction of the 5th asks about the 5th,
  finds the rate of the 1st because nothing newer exists, and caches it under the 5th —
  a transaction of the 6th does the same under the 6th. Two entries, both answering from
  one row. Retiring the entry for the day someone happened to convert on would leave the
  other serving a rate that no longer exists, which is the answer that looks most right
  of all.

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

  # Two transactions on one day, because that is when a cache answers at all. Different
  # days are different questions and get different entries — see below, where that is the
  # subject rather than a detail.
  Scenario: A second conversion is answered without reading the table again
    Given the rate for "KZT" on "2022-01-01" is 0.0025
    When a transaction of "400" KZT was posted on "2022-01-05"
    And a transaction of "400" KZT was posted on "2022-01-05"
    Then 2 transactions are stored
    And the month's total is "2.00" USD
    And the rate was resolved from the cache at least once

  # The reason the cache carries a generation counter rather than deleting keys. Both
  # entries answer from the rate of the 1st, they are stored under the days they were
  # asked about, and one refresh of the 1st has to retire the pair: a cache that retired
  # only what the sweeper last touched would keep serving the old rate to the 6th and
  # report nothing wrong anywhere.
  Scenario: One refresh retires every day cached under it
    Given the rate for "KZT" on "2022-01-01" is 0.0025
    When a transaction of "400" KZT was posted on "2022-01-05"
    And a transaction of "400" KZT was posted on "2022-01-06"
    And the rate for "KZT" on "2022-01-01" is refreshed to 0.0050
    And a transaction of "400" KZT was posted on "2022-01-05"
    Then the transaction of "2022-01-05" totals "1.00" USD
    And the month's total is "4.00" USD

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