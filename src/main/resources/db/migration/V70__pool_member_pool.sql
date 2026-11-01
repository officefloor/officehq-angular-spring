-- A pool's member is a person or another pool (member_pool_id, the person then null). A pool is a
-- member of at most one pool.
ALTER TABLE pool_member ALTER COLUMN person_id SET NULL;
ALTER TABLE pool_member ADD COLUMN member_pool_id BIGINT REFERENCES pool (id);
CREATE UNIQUE INDEX pool_member_pool_unique ON pool_member (member_pool_id);
ALTER TABLE pool_member ADD CONSTRAINT pool_member_one_check
    CHECK ((person_id IS NULL) <> (member_pool_id IS NULL));
