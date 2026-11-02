-- The pools of a sheet, each (trimmed) name at most once, and their members: people of the sheet,
-- each at most once in a pool, with a share percent. A person can be in several pools.
CREATE TABLE pool (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),
    name     VARCHAR(200) NOT NULL,
    CONSTRAINT pool_unique UNIQUE (sheet_id, name)
);

CREATE TABLE pool_member (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    pool_id   BIGINT       NOT NULL REFERENCES pool (id),
    person_id BIGINT       NOT NULL REFERENCES person (id),
    share     DECIMAL(5, 2) NOT NULL,
    CONSTRAINT pool_member_unique UNIQUE (pool_id, person_id)
);
