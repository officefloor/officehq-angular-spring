-- Each pool member's cut fixed when a period was closed or soft-closed, as it stood at that moment
-- (two decimals; empty for a member with no cut then), keyed by the member's place on the sheet: the
-- pool (a crew's by its negated id) and the person or pool. A member added later has none (shown
-- blank). Reopening removes them: the cuts at the period's end are live again.
CREATE TABLE period_pool_cut (
    period_id  BIGINT       NOT NULL REFERENCES period (id),
    member_key VARCHAR(300) NOT NULL,
    cut        VARCHAR(20)  NOT NULL,
    PRIMARY KEY (period_id, member_key)
);
