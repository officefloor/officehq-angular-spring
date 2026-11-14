-- A pool member may be another pool of the sheet instead of a person: exactly one of person_id and
-- member_pool_id is set. A pool is a member of at most one pool, never of itself through any chain,
-- and a chain of pools is at most three deep. A pool that is a member of another has no pot of its
-- own: its pot is its cut in the pool above.
ALTER TABLE pool_member ALTER COLUMN person_id DROP NOT NULL;
ALTER TABLE pool_member ADD COLUMN member_pool_id BIGINT REFERENCES pool (id);
CREATE UNIQUE INDEX pool_member_member_pool ON pool_member (member_pool_id);
