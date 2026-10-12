-- ShedLock's column is `lock_until`; V2 created the table with `locked_until`.
--
-- One underscore, and no test could see it: the table exists, the migration succeeds, the context
-- starts, and the first sweep fails with `column "lock_until" of relation "shedlock" does not
-- exist`. The lock is acquired on entry to `@SchedulerLock`, before the job body runs, so the daily
-- sweep never completed anywhere — while looking configured, scheduled and enabled. It was found by
-- the first test that ever called `refreshDaily()` directly, not by anything reading the schema.
--
-- V2 is not edited, because Flyway validates its checksum against every database that has already
-- applied it and a mismatch is a refusal to start. The correction has to be its own version.
alter table shedlock
    rename column locked_until to lock_until;

-- The index follows the column it was built on. Postgres renames a column without renaming the
-- index, which would leave a query reading `lock_until` sitting beside an index called
-- `ix_shedlock_locked_until` — an index nobody would find by looking at the column it indexes.
drop index ix_shedlock_locked_until;

create index ix_shedlock_lock_until on shedlock (lock_until);
