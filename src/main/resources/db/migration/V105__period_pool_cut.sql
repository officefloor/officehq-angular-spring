-- Each pool member's cut for a closed or soft-closed period, fixed when it was closed, as the pools stood then: one
-- row per member with a cut, keyed by the pool and the member. Removed when the period is reopened.
CREATE TABLE period_pool_cut (
    period_id  BIGINT         NOT NULL REFERENCES period (id),
    member_key VARCHAR(400)   NOT NULL,
    cut        DECIMAL(14, 2) NOT NULL,
    PRIMARY KEY (period_id, member_key)
);
