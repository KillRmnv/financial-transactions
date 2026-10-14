-- The two lookup tables were smallserial while every entity declared a Long id.
--
-- Nothing caught it until `spring.jpa.hibernate.ddl-auto` was set to validate, because
-- Hibernate never looked at the database: it generated SQL against the column it believed
-- in and PostgreSQL accepted the narrower width on every write. The mismatch was invisible
-- for exactly as long as it was harmless.
--
-- The schema changes rather than the entities, for three reasons. There is no recorded
-- reason for smallserial — V1 uses it without a word, unlike every other choice in that
-- file. Changing four columns in Java would spread a second identifier type (`Integer`)
-- through repositories, mappers, native queries and the reporting service for no gain.
-- And a currency lookup is filled from a provider's own list, once a week, for ever: a
-- ceiling of 32767 rows is not a constraint anyone would want to discover from a failed
-- insert in a deployment.
--
-- The sequences are widened too, which is the part that is easy to miss. smallserial
-- creates a sequence with max_value 32767, so widening the column alone would leave an
-- integer sequence feeding a bigint column and still run out at the same 32767 rows —
-- the insert would fail with a "bigint out of range" that names the sequence rather
-- than the decision that produced it.
--
-- The foreign keys are dropped and recreated because PostgreSQL will not alter the type of
-- a column another table references. `base_currency` is untouched: it references
-- currency(code), a varchar, not the identifier.
alter table currency
    alter column id type bigint;

alter table expense_category
    alter column id type bigint;

alter table expense_limit
    drop constraint expense_limit_currency_id_fkey,
    drop constraint expense_limit_category_id_fkey;

alter table expense_transaction
    drop constraint expense_transaction_currency_id_fkey,
    drop constraint expense_transaction_category_id_fkey;

alter table expense_limit
    alter column currency_id type bigint,
    alter column category_id type bigint;

alter table expense_transaction
    alter column currency_id type bigint,
    alter column category_id type bigint;

alter table expense_limit
    add constraint expense_limit_currency_id_fkey foreign key (currency_id) references currency (id),
    add constraint expense_limit_category_id_fkey foreign key (category_id) references expense_category (id);

alter table expense_transaction
    add constraint expense_transaction_currency_id_fkey foreign key (currency_id) references currency (id),
    add constraint expense_transaction_category_id_fkey foreign key (category_id) references expense_category (id);

-- `AS bigint` is what raises max_value; it resets to the type's default rather than
-- scaling the existing range, which is what a sequence this short has no use for.
alter sequence currency_id_seq as bigint;

alter sequence expense_category_id_seq as bigint;