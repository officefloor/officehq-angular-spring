-- Closing or soft-closing a period also fixes, at that moment, every pool member's cut for it: the cut
-- each member of each pool (a crew's under its id negated) showed then, by member key (the person's id,
-- or pool-<name> for a member pool). A pool with no row showed no cuts then. Reopening removes the rows:
-- the cuts for the period are live again.
CREATE TABLE period_pool_cut (
    period_id  BIGINT         NOT NULL REFERENCES period (id),
    pool_id    BIGINT         NOT NULL,
    member_key VARCHAR(300)   NOT NULL,
    cut        DECIMAL(12, 2) NOT NULL,
    PRIMARY KEY (period_id, pool_id, member_key)
);
