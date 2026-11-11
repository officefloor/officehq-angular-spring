-- The pools of a sheet, each name at most once (kept trimmed).
CREATE TABLE pool (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),
    name     VARCHAR(255) NOT NULL
);
CREATE UNIQUE INDEX pool_sheet_name ON pool (sheet_id, name);

-- The members of a pool, each person at most once, with their share percent (two decimals, above 0, at most 100).
CREATE TABLE pool_member (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    pool_id   BIGINT       NOT NULL REFERENCES pool (id),
    person_id BIGINT       NOT NULL REFERENCES person (id),
    share     DECIMAL(5, 2) NOT NULL
);
CREATE UNIQUE INDEX pool_member_pool_person ON pool_member (pool_id, person_id);
