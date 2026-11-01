-- Each pool member's cut fixed when its period was closed or soft-closed, as it stood at that moment; keyed by
-- the pool's id and the member's key within it (the person's id, or pool-<id> for a pool). Removed when the period
-- is reopened.
CREATE TABLE period_pool_cut (
    period_id  BIGINT         NOT NULL REFERENCES period (id),
    cut_key    VARCHAR(80)    NOT NULL,
    cut        DECIMAL(14, 2) NOT NULL,
    PRIMARY KEY (period_id, cut_key)
);
