Feature: The client-facing API over HTTP

  The specification requires one end-to-end API test that reproduces "case 1"
  through HTTP. This is it: the whole API is driven over a real port against
  PostgreSQL, with the external rates API replaced by a stub bean.

  Each returned row carries the limit the flag was actually computed against,
  which is not necessarily the limit installed today.

  Background:
    Given the account "0000000123" and the category "product"

  # The specification's own table carries two columns that disagree: «сумма транзакции» says 100 on
  # every row, and those sums do not produce the «остаток месячного лимита» beside them. The amounts
  # below are recovered from the остаток column — 900−800, 800−100, 100−0, 0−(−100) — and they are the
  # amounts that reproduce the flags the same table states. Requirement 6 asks for "the transactions of
  # 3 and 13 January", and only these amounts yield exactly two.
  #
  # It also lands on the boundary requirement 5 names: the first 13 January brings the month to exactly
  # 2000 against a limit of 2000, which is not exceeded. The second crosses it.
  Scenario: Case 1 over HTTP — only 3 and 13 January come back, with the limit that was exceeded
    Given a limit of "1000" USD was installed on "2022-01-01"
    And a transaction of "500" USD happened on "2022-01-02"
    And a transaction of "600" USD happened on "2022-01-03"
    And a limit of "2000" USD was installed on "2022-01-10"
    And a transaction of "100" USD happened on "2022-01-11"
    And a transaction of "700" USD happened on "2022-01-12"
    And a transaction of "100" USD happened on "2022-01-13"
    And a transaction of "100" USD happened on "2022-01-13"
    When the exceeded transactions for "2022-01" are requested
    Then the response is 200
    And the response holds 2 exceeded transactions
    And the exceeded transactions are on these dates:
      | date       |
      | 2022-01-03 |
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

  Scenario: The limits of an account come back with what the month has spent against them
    Given a limit of "2000" USD was installed on "2022-01-01"
    And a transaction of "500" USD happened on "2022-01-02"
    And a transaction of "600" USD happened on "2022-01-03"
    When the limits of the account are requested
    Then the response is 200
    And the response holds 2 limits
    And the limit for "product" reports "1100" spent and "900" remaining
    And the limit for "service" reports "0" spent and "1000" remaining

  # The report is driven by the categories rather than by the rows, so a category nobody
  # has spent on is still answered. Omitting it read as "no limit applies here", while
  # the first transaction in that category would in fact be measured against the same
  # standing 1000 USD — the omission and the absence were told apart by nobody.
  Scenario: A category with nothing installed reports the standing default
    Given the account "0000000460" and the category "product"
    And a transaction of "400" USD happened on "2022-01-05"
    When the limits of the account are requested
    Then the response holds 2 limits
    And the limit for "service" is the automatic fallback
    And the limit for "service" reports "1000" of the limit
    And the limit for "service" reports "0" spent and "1000" remaining

  Scenario: An account nothing has ever been posted for still has both categories
    When the limits of the account "0000000461" are requested
    Then the response is 200
    And the response holds 2 limits
    And the limit for "product" is the automatic fallback
    And the limit for "service" is the automatic fallback

  # The whole loop in one scenario, because every other limits scenario inspects the
  # report against limits that were installed first. This one starts from a transaction
  # and checks that the account it lands on is then described as having spent it — which
  # is what a client actually does, and what nothing proved.
  Scenario: A transaction is reported as spent against the limit of its category
    Given a limit of "1000" USD was installed on "2022-01-01"
    And a transaction of "600" USD happened on "2022-01-05"
    And a transaction of "900" USD happened on "2022-01-06"
    When the limits of the account are requested for the month "2022-01"
    Then the limit for "product" reports "1500" spent and "-500" remaining
    And the limit for "product" is not the automatic fallback
    And the response holds 2 exceeded transactions

  # A category nothing was installed for gets its standing limit the moment a
  # transaction needs one, so the report has to show it as spent against a default it
  # has never seen a row for.
  Scenario: A transaction into a category with no limit is measured against the standing one
    Given the account "0000000470" and the category "service"
    And a transaction of "1500" USD happened on "2022-01-05"
    When the limits of the account are requested for the category "service" and the month "2022-01"
    Then the limit for "service" is the automatic fallback
    And the limit for "service" reports "1500" spent and "-500" remaining
    And the response holds 1 exceeded transactions

  # The month is a parameter because transactions carry the date they were made, and
  # those dates are in the past. Asked without it, this account's full January reads as
  # a month with nothing spent in it — which is the answer a running service gives, and
  # the reason the report looked broken rather than empty.
  Scenario: Asking about a month reports that month, not the current one
    Given the service clock is at "2026-06-01"
    And the account "0000000471" and the category "product"
    And a transaction of "400" USD happened on "2022-01-05"
    When the limits of the account are requested
    Then the limit for "product" reports "0" spent and "1000" remaining
    When the limits of the account are requested for the month "2022-01"
    Then the limit for "product" reports "400" spent and "600" remaining
    When the limits of the account are requested for the month "2022-02"
    Then the limit for "product" reports "0" spent and "1000" remaining

  # The filter is the same question asked about one category rather than both.
  Scenario: A category can be asked for on its own
    Given a limit of "2000" USD was installed on "2022-01-01"
    And a transaction of "500" USD happened on "2022-01-02"
    When the limits of the account are requested for the category "product"
    Then the response is 200
    And the response holds 1 limits
    And the limit for "product" reports "500" spent and "1500" remaining

  Scenario: Asking for one category that has no limit still answers with the default
    Given a limit of "2000" USD was installed on "2022-01-01"
    When the limits of the account are requested for the category "service"
    Then the response holds 1 limits
    And the limit for "service" is the automatic fallback

  # An empty list would be the same answer as "no limit applies here", which is the one
  # answer the specification forbids. The category is checked where the report is built,
  # so it costs no round trip of its own.
  Scenario: A category the lookup does not hold is refused by the limits report
    When the limits of the account are requested for the category "gold"
    Then the response is 404
    And the response is a problem of type "not-found"

  Scenario: A category the lookup does not hold is refused by the exceeded report
    When the exceeded transactions for the current month are requested for the category "gold"
    Then the response is 404
    And the response is a problem of type "not-found"

  # An installed limit has a date; the standing 1000 USD fallback has none, because nothing
  # installed it. A null date on its own reads as "unknown", so the row says which of the two
  # it is.
  Scenario: The exceeded report takes the same category filter
    Given a limit of "1000" USD was installed on "2022-01-01" for the category "product"
    And a limit of "1000" USD was installed on "2022-01-01" for the category "service"
    And a transaction in the category "product" for "1500" USD happened on "2022-01-05"
    And a transaction in the category "service" for "1500" USD happened on "2022-01-05"
    When the exceeded transactions for the current month are requested for the category "service"
    Then the response holds 1 exceeded transactions
    And the 1st exceeded transaction is in the category "service"

  Scenario: An installed limit reports its installation date and is not the fallback
    Given the account "0000000123" and the category "product"
    And a limit of "1000" USD was installed on "2022-01-10"
    When the limits of the account are requested
    Then the limit for "product" is not the automatic fallback
    And the limit for "product" was installed on "2022-01-10"

  Scenario: The automatic fallback reports no date and says so
    Given the account "0000000123" and the category "product"
    And a transaction of "10" USD happened on "2022-01-10"
    When the limits of the account are requested
    Then the limit for "product" is the automatic fallback
    And the limit for "product" has no installation date

  Scenario: A converted transaction says which currency its sumInLedger is in
    Given the account "0000000456" and the category "product"
    And the provider answers "KZT" against "USD" at "0.0025" on "2022-01-01"
    And a limit of "2000" USD was installed on "2022-01-01"
    When a transaction in "KZT" for "400" USD happened on "2022-01-05"
    Then the response is 201
    And the transaction reports its ledger amount in "USD"

  Scenario: A rate published by the provider converts the transaction over the wire
    Given the account "0000000456" and the category "product"
    And the provider answers "KZT" against "USD" at "0.0025" on "2022-01-01"
    And a limit of "2000" USD was installed on "2022-01-01"
    When a transaction in "KZT" for "400" USD happened on "2022-01-05"
    Then the response is 201
    And the provider was asked for the symbol "KZT/USD"
    And the only transaction is converted at "1.00" USD

  # Twelve Data sends no previous_close at all — its answers carry datetime, open, high, low, close
  # and nothing else — so the fallback the specification names is built by the adapter out of a window.
  # The stub used to answer with previous_close because someone put it there, and the field was read,
  # read correctly, and never once populated by anything real.
  Scenario: A day with no close of its own is priced from the last close before it
    Given the account "0000000457" and the category "product"
    And the provider has no close for "KZT" against "USD" on "2022-01-01"
    And the provider answers a window for "KZT" against "USD" with:
      | date       | close  |
      | 2021-12-29 | 0.0024 |
      | 2021-12-30 | 0.0025 |
    And a limit of "2000" USD was installed on "2022-01-01"
    When a transaction in "KZT" for "400" USD happened on "2022-01-05"
    Then the response is 201
    And the only transaction is converted at "1.00" USD

  Scenario: An unreachable provider costs the rate, not the transaction
    Given the account "0000000458" and the category "product"
    And the provider is unreachable
    And a limit of "2000" USD was installed on "2022-01-01"
    When a transaction in "KZT" for "400" USD happened on "2022-01-05"
    Then the response is 201
    And the only transaction is stored without a flag

  # The same check as features/service/concurrency.feature, over HTTP, and deliberately kept as well:
  # the HTTP round trip widens the window in which two posts can read the same total, so this scenario
  # caught the removed row lock in three runs out of three where the service-level one caught it in two
  # out of four. Where a check can be made reliable, it should be.
  Scenario: Concurrent posts over HTTP all land, and the month's total adds up
    Given the account "0000000470" and the category "product"
    And a limit of "1000" USD was installed on "2022-01-01"
    When 20 requests post a transaction of "100" USD on "2022-01-15" at the same time
    Then 20 transactions are stored
    And the month's total is "2000" USD
    And exactly 10 transactions are flagged

  # Optional requirement 1*: a batch resolves its rates in parallel. What is asserted here is the
  # outcome — every transaction stored, converted, and the provider asked once per pair — because the
  # concurrency itself is not observable from outside without a timing assertion.
  Scenario: A batch spanning several currencies stores and converts every transaction
    Given the account "0000000481" and the category "product"
    And a limit of "1000" USD was installed on "2022-01-01"
    And the provider answers "KZT" against "USD" at "0.0025" on "2022-01-01"
    And the provider answers "RUB" against "USD" at "0.0130" on "2022-01-01"
    When a batch is posted with these transactions:
      | currency | sum   | date       |
      | KZT      | 400   | 2022-01-05 |
      | RUB      | 100   | 2022-01-05 |
      | USD      | 250.5 | 2022-01-06 |
    Then the response is 201
    And the response holds 3 stored transactions
    And the 1st stored transaction is in currency "KZT"
    And the 1st stored transaction totals "1.00" USD
    And the 2nd stored transaction is in currency "RUB"
    And the 2nd stored transaction totals "1.30" USD
    And the 3rd stored transaction is in currency "USD"
    And the 3rd stored transaction totals "250.50" USD
    And 3 transactions are stored

  # Three transactions in one batch need one provider call, not three. The provider is made slow on
  # purpose: the coordinator collapses a call only while it is still in flight, and an instant answer is
  # finished before the next transaction starts asking — the assertion would then depend on how busy the
  # machine is rather than on the code.
  Scenario: A batch asks the provider once for a pair it is asked twice about
    Given the account "0000000482" and the category "product"
    And a limit of "1000" USD was installed on "2022-01-01"
    And the provider answers "KZT" against "USD" at "0.0025" on "2022-01-01"
    And the provider answers slowly
    When a batch is posted with these transactions:
      | currency | sum   | date       |
      | KZT      | 400   | 2022-01-05 |
      | KZT      | 400   | 2022-01-05 |
      | KZT      | 400   | 2022-01-06 |
    Then the response is 201
    And the response holds 3 stored transactions
    And the provider was asked 1 times for the symbol "KZT/USD"
    And 3 transactions are stored

  Scenario: A batch with an invalid transaction is refused and nothing is stored
    When a batch is posted with these transactions:
      | currency | sum   | date       |
      | USD      | 100   | 2022-01-05 |
      | USD      | -5    | 2022-01-05 |
    Then the response is 400
    And the response is a problem of type "validation-failed"
    And 0 transactions are stored

  Scenario: A batch naming a currency the lookup does not have is refused
    Given the account "0000000483" and the category "product"
    When a batch is posted with these transactions:
      | currency | sum   | date       |
      | KZT      | 400   | 2022-01-05 |
      | ZZZ      | 400   | 2022-01-05 |
    Then the response is 404
    And the response is a problem of type "not-found"
    And 0 transactions are stored

  # Twelve Data has no KZT/USD — the symbol does not exist and answers 404 — while USD/KZT is there.
  # Found against the live provider; every stub until then answered only with successes, so the
  # adapter's inverse fallback had nothing in the suite to be exercised by.
  Scenario: A pair the provider publishes the other way round is converted by division
    Given the account "0000000484" and the category "product"
    And the provider does not publish "KZT" against "USD"
    And the provider answers "USD" against "KZT" at "450.0000" on "2022-01-01"
    When a transaction in "KZT" for "10000.45" USD happened on "2022-01-05"
    Then the response is 201
    And the only transaction is converted at "22.22322222" USD
    And the provider was asked 1 times for the symbol "KZT/USD"
    And the provider was asked 1 times for the symbol "USD/KZT"

  # The row keeps what the multiplication produced, and the response rounds it. The two
  # halves are deliberately asserted by different steps: "the transaction is converted
  # at" reads the stored row, "the stored transaction totals" reads the answer, and a
  # unit that rounds on the way in would satisfy the second and break the first.
  Scenario: What the row keeps exactly, the response rounds for reading
    Given the account "0000000486" and the category "product"
    And the provider answers "KZT" against "USD" at "0.002222" on "2022-01-01"
    When a transaction in "KZT" for "10000.45" USD happened on "2022-01-05"
    Then the response is 201
    And the only transaction is converted at "22.22099990" USD
    And the accepted transaction answers with a ledger amount of "22.22" USD
    And the transaction reports its ledger amount in "USD"

  Scenario: A pair the provider does not publish in either direction is stored without a flag
    Given the account "0000000485" and the category "product"
    And the provider does not publish "KZT" against "USD"
    And the provider does not publish "USD" against "KZT"
    When a transaction in "KZT" for "10000.45" USD happened on "2022-01-05"
    Then the response is 201
    And the only transaction is stored without a flag
