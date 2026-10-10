-- ShedLock: one holder at a time for a named lock, across every instance of the deployment.
--
-- The daily rate sweep is the only user. Without this table two replicas each run it at the same
-- minute and the provider is paid twice for the same rate — which is the cost the specification goes
-- out of its way to avoid ("each external request costs money").
--
-- `locked_until` rather than a boolean flag: a process that dies holding the lock must not block the
-- next sweep forever, so the row carries the moment the lock goes stale and ShedLock treats anything
-- past it as available.
--
-- `locked_by` names the instance so a stuck lock can be traced to a host rather than guessed at.
create table shedlock
(
    name       varchar(64)  not null primary key,
    locked_at  timestamptz  not null,
    locked_by  varchar(255) not null,
    locked_until timestamptz not null
);

-- ShedLock expires a lock by updating locked_until, so the lookup it does is by primary key and this
-- index is not on its path. It is here for the manual query: "who is holding the sweep right now".
create index ix_shedlock_locked_until on shedlock (locked_until);