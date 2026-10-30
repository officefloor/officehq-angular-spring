-- The pools on a sheet, each name once, and their members: people of the sheet, each in a pool at most
-- once, with a share percent (more than 0.00, at most 100.00). A person can be in several pools.
CREATE TABLE pool (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),
    name     VARCHAR(100) NOT NULL,
    CONSTRAINT pool_unique UNIQUE (sheet_id, name)
);
CREATE TABLE pool_member (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    pool_id   BIGINT NOT NULL REFERENCES pool (id),
    person_id BIGINT NOT NULL REFERENCES person (id),
    share     DECIMAL(5, 2) NOT NULL CHECK (share > 0 AND share <= 100),
    CONSTRAINT pool_member_unique UNIQUE (pool_id, person_id)
);
