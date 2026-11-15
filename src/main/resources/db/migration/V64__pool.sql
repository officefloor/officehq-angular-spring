-- A pool on the sheet: a name (at most once per sheet, compared ignoring case) and its members.
CREATE TABLE pool (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    name      VARCHAR(200) NOT NULL
);
CREATE INDEX pool_sheet ON pool (sheet_id, id);

-- A member of a pool: a person of the sheet (at most once per pool) with a share percent
-- (more than 0.00, at most 100.00). A person may be in several pools.
CREATE TABLE pool_member (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    pool_id    BIGINT NOT NULL REFERENCES pool (id),
    person_id  BIGINT NOT NULL REFERENCES person (id),
    share      DECIMAL(5, 2) NOT NULL CHECK (share > 0 AND share <= 100),
    UNIQUE (pool_id, person_id)
);
