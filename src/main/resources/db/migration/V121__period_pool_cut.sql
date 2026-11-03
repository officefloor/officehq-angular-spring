-- Each pool member's cut for a closed or soft-closed period, fixed when it was closed: their cut
-- as it stood at that moment, keyed "pool-id:member-key" (member key p<person-id> or q<pool-id>).
-- A member with no cut then has no row. The rows go when the period is reopened.
CREATE TABLE period_pool_cut (
    period_id BIGINT         NOT NULL REFERENCES period (id),
    cut_key   VARCHAR(100)   NOT NULL,
    cut       DECIMAL(14, 2) NOT NULL,
    PRIMARY KEY (period_id, cut_key)
);
-- The sheet's history keeps them too, as text: "pool-id:member-key=cut", separated by ";".
ALTER TABLE period_revision ADD COLUMN pool_cuts VARCHAR(20000) DEFAULT '' NOT NULL;
