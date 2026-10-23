-- A rate is kept as versions: setting a rate for a grade and from-date that already has one keeps
-- the value it replaces as an older version and makes the new value the next version. The rate row
-- holds the newest version (the one in force) and its number, counted from 1; rate_version holds
-- every older one.
ALTER TABLE rate ADD COLUMN version INT DEFAULT 1 NOT NULL;
ALTER TABLE rate ADD CONSTRAINT rate_version_positive CHECK (version >= 1);
CREATE TABLE rate_version (
    id      BIGINT AUTO_INCREMENT PRIMARY KEY,
    rate_id BIGINT        NOT NULL REFERENCES rate (id),
    version INT           NOT NULL CHECK (version >= 1),
    pct     DECIMAL(5, 2) NOT NULL CHECK (pct >= 0),
    to_date DATE
);
CREATE UNIQUE INDEX rate_version_rate_version ON rate_version (rate_id, version);
