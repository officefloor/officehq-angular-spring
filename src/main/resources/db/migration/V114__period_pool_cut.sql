-- Each pool member's cut fixed when the period was closed or soft-closed, by the pool and the member
-- (pool:<id> or crew:<id>, then person:<id> or pool:<id>, joined by '/'). A member with no row here had
-- no cut then. The rows are removed when the period is reopened.
CREATE TABLE period_pool_cut (
    period_id BIGINT         NOT NULL REFERENCES period (id),
    cut_key   VARCHAR(100)   NOT NULL,
    cut       DECIMAL(12, 2) NOT NULL,
    PRIMARY KEY (period_id, cut_key)
);
