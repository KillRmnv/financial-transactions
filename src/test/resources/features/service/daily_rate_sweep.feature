Feature: The daily sweep stores the day somebody will ask for

  Point 3 of the specification asks for the close of the day, and for the last close
  before it when that day has none of its own. Both of those are decided in the
  provider adapter, and neither is what this file is about.

  What it pins down is the third thing — the one the specification never states
  because it follows from the first two: the row has to be keyed by the day the
  transaction path asks for. A rate is looked up for the day of the expense, so a row
  dated anything else is found by `findForDay` never, and only through
  `findLatestOnOrBefore` — "the last one not later than", which is a price from
  further back than the day's own. The day's close would exist and go unused.

  The row used to be dated the first of the month, because that is what the conversion
  asked for then. It no longer is, and the sweep moved with it.

  The dates are absolute so the arithmetic is visible rather than implied. The clock
  reads Sunday 2026-10-11, so yesterday is Saturday 2026-10-10 — which has no close of
  its own, and is priced by the Friday before it, 2026-10-09. The first of the month is
  2026-10-01 and nobody asks for it. All three dates are asserted, because a sweep
  writing any one of them instead of 2026-10-09 satisfies none of the claims below.

  One scenario rather than three, and not because of the lock: the step calls the job
  through its target object, past the @SchedulerLock advice, so the sweep can run once
  per claim here. It is one scenario because all three claims are about the same run —
  what the sweep wrote, that a transaction reads that row, and that a day it did not
  reach still costs a call. Splitting them would let each pass against a different one.

  Scenario: The sweep stores the day that prices yesterday, and that row is what the transaction reads
    Given the account "0000000123" and the category "product"
    And the clock reads "2026-10-11T12:00:00Z"
    And the provider answers every rate lookup

    When the daily rate sweep runs
    Then the provider was asked for a rate
    And a rate for "KZT" is stored for "2026-10-09"
    And no rate for "KZT" is stored for "2026-10-10"
    And no rate for "KZT" is stored for "2026-10-01"

    # The row is one thing; being found by the transaction that needs it is another.
    # A rate stored somewhere nobody reads costs the same call as no rate at all, so
    # the call is what is asserted — an unread row answers the four lines above
    # perfectly. Nothing here can be served from memory either: the coordinator only
    # hands one answer to callers that overlap in time, and drops it after.
    When the provider's rate lookups are forgotten
    And a transaction in "KZT" for "500" USD happened on "2026-10-10"
    Then the provider was asked for rates 0 times
    And the rate used for "2026-10-10" is 0.00222

    # The other half: a day the sweep did not reach still costs a call, rather than
    # being answered from the one row that happens to be there.
    When the provider's rate lookups are forgotten
    And a transaction in "KZT" for "500" USD happened on "2026-10-05"
    Then the provider was asked for rates 1 times
    And the rate used for "2026-10-05" is 0.00222