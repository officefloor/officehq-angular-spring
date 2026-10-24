-- A rate is kept as versions: setting a rate for a grade and from-date that already exist keeps the
-- value it replaces as an older version. The rate row holds the newest version (the one in force) and
-- its version number, which is also how many versions it has; rate_version holds the older ones.
ALTER TABLE rate ADD COLUMN version INT NOT NULL DEFAULT 1;
CREATE TABLE rate_version (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    rate_id  BIGINT        NOT NULL REFERENCES rate (id),
    version  INT           NOT NULL,
    pct      DECIMAL(7, 2) NOT NULL CHECK (pct >= 0),
    to_date  DATE
);
CREATE UNIQUE INDEX rate_version_rate ON rate_version (rate_id, version);
