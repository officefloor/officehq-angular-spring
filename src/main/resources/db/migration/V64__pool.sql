-- The sheet's pools, each name at most once, and their members: a person with a share percent, each person at most
-- once in a pool though they may be in several. A retired member's share counts as 0.00 toward the pool's total.
CREATE TABLE pool (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),
    name     VARCHAR(140) NOT NULL
);
CREATE UNIQUE INDEX pool_name ON pool (sheet_id, name);

CREATE TABLE pool_member (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    pool_id   BIGINT       NOT NULL REFERENCES pool (id),
    person_id BIGINT       NOT NULL REFERENCES person (id),
    share     DECIMAL(5, 2) NOT NULL CHECK (share > 0 AND share <= 100)
);
CREATE UNIQUE INDEX pool_member_once ON pool_member (pool_id, person_id);
