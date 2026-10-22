-- A pool's member may be another pool instead of a person, with a share like a person's. A pool is a
-- member of at most one pool; exactly one of person_id and member_pool_id is set.
ALTER TABLE pool_member ALTER COLUMN person_id SET NULL;
ALTER TABLE pool_member ADD COLUMN member_pool_id BIGINT REFERENCES pool (id);
ALTER TABLE pool_member ADD CONSTRAINT pool_member_one_kind
    CHECK ((person_id IS NULL) <> (member_pool_id IS NULL));
CREATE UNIQUE INDEX pool_member_member_pool ON pool_member (member_pool_id);
