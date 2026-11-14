-- A sheet's pools: each a name, on a sheet at most once, and its members. A member is a person of
-- the sheet with a share percent (more than 0.00, at most 100.00); a person is in a pool at most
-- once, and may be in several pools. A retired member's share counts as 0.00 toward the pool's
-- total, which the screen works out from the people as they are.
CREATE TABLE pool (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(200) NOT NULL REFERENCES sheet (id),
    name VARCHAR(255) NOT NULL
);
CREATE UNIQUE INDEX pool_sheet_name ON pool (sheet_id, name);

CREATE TABLE pool_member (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    pool_id BIGINT NOT NULL REFERENCES pool (id),
    person_id BIGINT NOT NULL REFERENCES person (id),
    share DECIMAL(5, 2) NOT NULL
);
CREATE UNIQUE INDEX pool_member_pool_person ON pool_member (pool_id, person_id);
