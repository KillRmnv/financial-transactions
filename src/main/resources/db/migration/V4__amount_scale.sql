-- Widens every amount column so a low-rate currency survives the conversion.
--
-- The reason is arithmetic, not preference. A transaction converts as ledger_sum = sum × rate,
-- and a KZT amount converts to a fraction of a dollar: 10 KZT at 0.0022 is 0.022 USD, and
-- 1 IDR at 0.000061 is 0.000061. Rounded to two decimal places both become 0.00, the row
-- passes every check constraint in the schema, and the month's running total silently does not
-- move — which means the limit is measured against a sum that was thrown away. A numeric(14, 2)
-- ceiling also refused amounts large enough to be legitimate for a corporate ledger.
--
-- Eight decimal places is the same scale the rate columns already use (numeric(19, 8)), so the
-- product of two numbers stored at that scale is representable without loss.
--
-- Rounding for display is a separate decision and stays where it was: `app.ledger.amount-scale`
-- and LedgerPolicy.round, which concern the API response rather than the stored total. Nothing
-- here changes what a client is shown.
--
-- All three columns are widened rather than only ledger_sum, because applied_rate is the rate
-- this transaction was priced at and the sum is what the account reported: an amount a client
-- is allowed to send and an amount the ledger stores must be the same width, or the two disagree
-- about what a valid number is.

alter table expense_limit
    alter column amount type numeric(20, 8);

alter table expense_transaction
    alter column amount type numeric(20, 8);

alter table expense_transaction
    alter column ledger_sum type numeric(20, 8);

alter table expense_transaction
    alter column applied_rate type numeric(20, 8);
