-- A pool member may be another pool of the sheet instead of a person: exactly one of person_id and
-- member_pool_id is set. A pool is a member of at most one pool.
ALTER TABLE pool_member ALTER COLUMN person_id SET NULL;
ALTER TABLE pool_member ADD COLUMN member_pool_id BIGINT REFERENCES pool (id);
ALTER TABLE pool_member ADD CONSTRAINT pool_member_pool_unique UNIQUE (member_pool_id);
