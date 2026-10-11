-- Adds the weight a transaction carries against its limit.
--
-- A weighted limit spends the same money unevenly: a service expense may count half
-- against the month's allowance while a product expense counts all of it. The rule
-- then asks whether the *weighted* total has passed the limit, not whether the money
-- has.
--
-- The weight is stored on the transaction rather than looked up from the category on
-- every read, for the reason `limit_id` is stored for the same reason: a later change
-- to the configuration must not rewrite a flag that was already decided. Summing the
-- weight from the category table would mean that raising a weight in June changed what
-- January's transactions mean, and every flag before it would silently become wrong.
--
-- One means "counts for its full value", which is the whole of the behaviour before
-- this column, so a deployment that configures no weights is unaffected — the sum is
-- multiplied by one.
--
-- Zero is refused rather than allowed as a way of exempting a category: an exemption
-- has a name, and a weight of zero would mean that a category's rows were in the
-- month total and contributing nothing to it, which is two statements about one
-- number. A transaction that should not count is not stored, and if that changes, it
-- changes as a decision rather than as a magic multiplier.

alter table expense_transaction
    add column limit_weight numeric(20, 8) not null default 1;

alter table expense_transaction
    add constraint ck_expense_transaction_limit_weight_positive check (limit_weight > 0);
