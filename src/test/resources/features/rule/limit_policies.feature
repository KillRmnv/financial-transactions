Feature: Which rule measures a limit

  A limit is measured by a rule, and the rule is what makes "how much has been spent"
  a question with a shape. Two are available: the one the specification describes, where
  every transaction counts for what it cost, and a weighted one, where a category can be
  worth a fraction of its price against the allowance.

  **The weight has to be decided by the rule, not handed to it.** A weight is also what
  gets written onto the row, so that a weight changed later cannot rewrite a flag already
  decided. If the caller looked the weight up and passed it in, the stored weight and the
  compared weight would be two values that agree today — and the day they do not, the
  month's total would include a transaction weighted one way and a flag decided on another.

  **Weights configured under the monthly-sum rule are not read, and that is deliberate.**
  The alternative — honour them anyway — would mean a deployment that set a weight and
  chose not to switch rules gets half of each, with nothing in the logs saying so. A
  weight that is not in force reads as one.

  The comparison itself is the specification's, taken from `LimitFlagRule`, so the truth
  table in the other feature holds for both rules and neither can drift into a second
  opinion about whether equality is exceeded.

  Scenario: Spending exactly the limit is not exceeded
    Given the monthly-sum rule is in force, with "service" weighing "0.5" configured anyway
    And the month has already spent "700" USD
    And a transaction of "300" USD is posted
    And the limit in effect is "1000" USD
    Then the rule says the limit is not passed

  Scenario: Spending past the limit is exceeded
    Given the monthly-sum rule is in force, with "service" weighing "0.5" configured anyway
    And the month has already spent "700" USD
    And a transaction of "300.01" USD is posted
    And the limit in effect is "1000" USD
    Then the rule says the limit is passed

  # The same two rows under the weighted rule, with service worth half its price. The
  # spending that exactly filled the limit is now nowhere near it, and what did exceed it
  # is unchanged — a category the operator left out counts for its full value.
  Scenario: A weighted category spends half against its allowance
    Given the weighted rule is in force with "service" weighing "0.5"
    And the month has already spent "700" USD
    And a transaction of "300" USD is posted
    And the limit in effect is "1000" USD
    Then the rule says the limit is not passed

  Scenario: A weighted category can still pass its allowance
    Given the weighted rule is in force with "service" weighing "0.5"
    And the month has already spent "700" USD
    And a transaction of "601" USD is posted
    And the limit in effect is "1000" USD
    Then the rule says the limit is passed

  Scenario: A category the weights leave out counts for its full value
    Given the weighted rule is in force with "service" weighing "0.5"
    And the transaction is in category "product"
    And the month has already spent "700" USD
    And a transaction of "301" USD is posted
    And the limit in effect is "1000" USD
    Then the rule says the limit is passed

  Scenario: The weight belongs to the rule, and is what the row is stored with
    Given the weighted rule is in force with "service" weighing "0.5"
    Then a transaction in "service" counts as "0.5" against its limit
    And a transaction in "product" counts as "1" against its limit

  # The configuration says 0.5 and the rule says one. Nothing reconciles the two, which
  # is the point: a weight that is not in force reads as one rather than as half of what
  # the operator typed.
  Scenario: Weights configured under the monthly-sum rule are not read
    Given the monthly-sum rule is in force, with "service" weighing "0.5" configured anyway
    Then a transaction in "service" counts as "1" against its limit
