-- Closing or soft-closing a period also fixes every pool member's cut for it as it stood at that
-- moment: one line per member of a pool (pool_id) or of a crew's pool (crew_id), the member a person
-- (person_id) or a pool (member_pool_id), with its cut (null: none). Reopening the period removes its
-- lines: the cuts are live again.
CREATE TABLE period_pool_cut (
    period_id      BIGINT         NOT NULL REFERENCES period (id),
    pool_id        BIGINT,
    crew_id        BIGINT,
    person_id      BIGINT,
    member_pool_id BIGINT,
    cut            DECIMAL(14, 2)
);
