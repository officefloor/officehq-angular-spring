-- A pool's member may be another pool of the sheet instead of a person, with a share like a person's.
-- A pool is a member of at most one pool; each member is either a person or a pool.
ALTER TABLE pool_member ALTER COLUMN person_id SET NULL;
ALTER TABLE pool_member ADD COLUMN member_pool_id BIGINT REFERENCES pool (id);
ALTER TABLE pool_member ADD CONSTRAINT pool_member_one CHECK ((person_id IS NULL) <> (member_pool_id IS NULL));
ALTER TABLE pool_member ADD CONSTRAINT pool_member_pool_once UNIQUE (member_pool_id);
