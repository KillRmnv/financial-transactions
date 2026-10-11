Feature: One fetch per pair and day, whichever way round it is asked

  Every external rate costs money and takes seconds, so two callers wanting the same
  number should produce one call between them rather than two. `RateFetchCoordinator`
  holds the call that is already in flight and lets the second caller join it.

  **The key names the pair without an orientation.** `KZT/USD` and `USD/KZT` are one
  question asked backwards — the provider answers both from one number, and Twelve Data
  has no `KZT/USD` symbol at all, so that orientation is reached by dividing. With the
  orientation in the key, two callers on either side paid for two calls.

  **A shared key means the answer can arrive upside down**, and that is the risk the
  sharing introduces: the caller that joined did not do the work, and the rate it is
  handed quotes the other pair. Handing it over unchanged would store a number off by the
  square of the rate under the right pair, and nothing in the schema objects. So the
  answer is turned round on the way out — and only when the pair is *exactly* the reverse
  of what was asked, since a rate naming neither side is a provider defect to be seen
  rather than one to paper over with a division.

  The first caller is held inside the provider call until the second has reached the
  map, so the two overlap. The counts below rest on that overlap; the orientations do
  not, and are what a wrong answer would break.

  Scenario: Two callers on one pair make one call between them
    Given the provider answers "KZT" against "USD" at "0.0022"
    When two callers ask at once in the same orientation, "KZT" against "USD"
    Then the provider was asked 1 times
    And both callers get a rate
    And the caller that asked "KZT" against "USD" gets "0.0022"

  Scenario: The same pair asked both ways round still makes one call
    Given the provider answers "USD" against "KZT" at "450"
    When two callers ask at once, "KZT" against "USD" and "USD" against "KZT"
    Then the provider was asked 1 times
    And both callers get a rate
    And the caller that asked "KZT" against "USD" gets "0.002222222222"
    And the caller that asked "USD" against "KZT" gets "450"

  # The joining caller never reached the provider, so what it is handed arrives quoting
  # the other pair. Without the turn-round this is a factor of the square of the rate,
  # and every check the schema has passes on it.
  Scenario: A caller that joined is answered in the orientation it asked for
    Given the provider answers "KZT" against "USD" at "0.25"
    When two callers ask at once, "KZT" against "USD" and "USD" against "KZT"
    Then the caller that asked "USD" against "KZT" gets "4"
    And the caller that asked "KZT" against "USD" gets "0.25"
