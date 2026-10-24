-- The pools on a sheet, one row each by name (kept without the spaces around it).
CREATE TABLE sheet_pool (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),
    name     VARCHAR(200) NOT NULL,
    CONSTRAINT sheet_pool_unique UNIQUE (sheet_id, name)
);
-- A pool's members: people on the pool's sheet, each at most once per pool, with their share percent
-- (more than 0.00 and at most 100.00). A person can be in several pools.
CREATE TABLE sheet_pool_member (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    pool_id   BIGINT       NOT NULL REFERENCES sheet_pool (id),
    person_id BIGINT       NOT NULL REFERENCES person (id),
    share     DECIMAL(5, 2) NOT NULL,
    CONSTRAINT sheet_pool_member_unique UNIQUE (pool_id, person_id)
);
