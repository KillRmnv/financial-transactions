-- Which scheduled jobs are enabled is a row in the database, not a property.
--
-- @ConditionalOnProperty is evaluated once when the context registers its bean definitions, so a
-- property here would mean restarting the application to stop paying for a call that happens once
-- a year. The flag is read when the job fires, and switching it off is an UPDATE rather than a restart.
--
-- A restart is avoided because the only thing a disabled job does is return before it calls the
-- provider. The wakeup itself costs nothing; the HTTP call is what costs, and there is none.
--
-- The state is shared by every replica. A flag in a process's memory would apply only to the replica
-- it was set on and leave the others still paying, which is worse than having no flag at all.
--
-- name is a natural key: nothing references this table, and the code addresses rows by the name it
-- already knows. A surrogate id would be a column nobody reads.
create table scheduled_job
(
    name        varchar(64) primary key,
    enabled     boolean     not null default true,
    description varchar(255) not null,
    -- No updated_at: the now() default fires on insert only, and the column would quietly go stale
    -- on a switch. Who and when is answered by the log of the job that skipped, which is written
    -- every time and is therefore more honest than a date that stopped moving.
    constraint ck_scheduled_job_known check (name in ('daily-rates', 'weekly-currency-import'))
);

insert into scheduled_job (name, enabled, description)
values ('daily-rates', true,
        'Daily sweep of the rates for every active currency_pair. The specification requires it.'),
       ('weekly-currency-import', true,
        'Re-reads the provider''s currency list, which costs credits and changes about once a year.');
