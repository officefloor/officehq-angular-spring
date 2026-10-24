-- A pool's member may be another pool on the same sheet instead of a person: exactly one of person_id and
-- member_pool_id is set. A pool is a member of at most one pool.
ALTER TABLE sheet_pool_member ALTER COLUMN person_id SET NULL;
ALTER TABLE sheet_pool_member ADD COLUMN member_pool_id BIGINT REFERENCES sheet_pool (id);
ALTER TABLE sheet_pool_member ADD CONSTRAINT sheet_pool_member_one
    CHECK ((person_id IS NULL) <> (member_pool_id IS NULL));
ALTER TABLE sheet_pool_member ADD CONSTRAINT sheet_pool_member_pool_unique UNIQUE (member_pool_id);
