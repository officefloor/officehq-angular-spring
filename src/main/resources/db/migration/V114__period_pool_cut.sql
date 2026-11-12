-- Each pool member's cut for a period, fixed when the period was closed or soft-closed: the cut the member had in the
-- pool at that moment. The pool by its id (a crew's id negated), the member as person:<id> or pool:<id>. One row per
-- member that had a cut then; none while the period is open.
CREATE TABLE period_pool_cut (
    period_id BIGINT         NOT NULL REFERENCES period (id),
    pool_id   BIGINT         NOT NULL,
    member    VARCHAR(40)    NOT NULL,
    cut       DECIMAL(14, 2) NOT NULL,
    PRIMARY KEY (period_id, pool_id, member)
);
