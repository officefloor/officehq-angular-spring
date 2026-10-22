-- A pool on a sheet: a name (trimmed, once per sheet) and its members.
CREATE TABLE pool (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(64)  NOT NULL REFERENCES sheet (id),
    name     VARCHAR(100) NOT NULL
);
CREATE UNIQUE INDEX pool_sheet_name ON pool (sheet_id, name);

-- A pool's member: a person on the sheet, in the pool at most once, with a share percent (more than
-- 0.00, at most 100.00). A person may be in several pools.
CREATE TABLE pool_member (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    pool_id   BIGINT       NOT NULL REFERENCES pool (id),
    person_id BIGINT       NOT NULL REFERENCES person (id),
    share     DECIMAL(5, 2) NOT NULL
);
CREATE UNIQUE INDEX pool_member_pool_person ON pool_member (pool_id, person_id);
