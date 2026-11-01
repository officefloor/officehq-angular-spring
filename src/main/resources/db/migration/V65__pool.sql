-- The sheet's pools, each name once, stored without surrounding spaces.
CREATE TABLE pool (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),
    name     VARCHAR(200) NOT NULL
);
CREATE UNIQUE INDEX pool_unique ON pool (sheet_id, name);
-- A pool's members, each person at most once in a pool, with their share percent (two decimals).
CREATE TABLE pool_member (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    pool_id   BIGINT        NOT NULL REFERENCES pool (id),
    sheet_id  VARCHAR(100)  NOT NULL REFERENCES sheet (id),
    person_id BIGINT        NOT NULL REFERENCES person (id),
    share     DECIMAL(5, 2) NOT NULL
);
CREATE UNIQUE INDEX pool_member_unique ON pool_member (pool_id, person_id);
