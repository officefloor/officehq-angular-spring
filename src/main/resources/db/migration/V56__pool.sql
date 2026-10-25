-- The sheet's pools: a name (stored trimmed, once per sheet) and its members, each a person of the
-- sheet with a share percent (more than 0.00, at most 100.00). A person is in a pool at most once, and
-- may be in several pools.
CREATE TABLE pool (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),
    name     VARCHAR(200) NOT NULL
);
CREATE UNIQUE INDEX pool_once ON pool (sheet_id, name);

CREATE TABLE pool_member (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    pool_id   BIGINT        NOT NULL REFERENCES pool (id),
    person_id BIGINT        NOT NULL REFERENCES person (id),
    share     DECIMAL(5, 2) NOT NULL CHECK (share > 0 AND share <= 100)
);
CREATE UNIQUE INDEX pool_member_once ON pool_member (pool_id, person_id);
