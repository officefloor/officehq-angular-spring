-- A rate is kept as versions: setting a rate for a grade and from-date that already has one keeps
-- the value it replaces as an older version. The rate row is always the newest version (the one in
-- force) and counts its versions; the older ones are kept here, numbered from 1.
ALTER TABLE rate ADD COLUMN version INT NOT NULL DEFAULT 1;
CREATE TABLE rate_version (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    rate_id   BIGINT        NOT NULL REFERENCES rate (id),
    version   INT           NOT NULL,
    pct       DECIMAL(7, 2) NOT NULL,
    to_date   DATE,
    CONSTRAINT rate_version_unique UNIQUE (rate_id, version)
);
