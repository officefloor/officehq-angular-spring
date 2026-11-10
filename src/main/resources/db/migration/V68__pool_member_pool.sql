-- A pool member may be another pool instead of a person: exactly one of person_id and
-- member_pool_id is set. A pool is a member of at most one pool. A member carries its pool's sheet,
-- so a pool member (with no person) is kept with the sheet's rows like any other.
ALTER TABLE pool_member ADD COLUMN sheet_id VARCHAR(100) REFERENCES sheet (id);
UPDATE pool_member SET sheet_id = (SELECT p.sheet_id FROM pool p WHERE p.id = pool_member.pool_id);
ALTER TABLE pool_member ALTER COLUMN sheet_id SET NOT NULL;
ALTER TABLE pool_member ALTER COLUMN person_id DROP NOT NULL;
ALTER TABLE pool_member ADD COLUMN member_pool_id BIGINT REFERENCES pool (id);
ALTER TABLE pool_member ADD CONSTRAINT pool_member_one_kind
    CHECK ((person_id IS NULL) <> (member_pool_id IS NULL));
ALTER TABLE pool_member ADD CONSTRAINT pool_member_pool_once UNIQUE (member_pool_id);
