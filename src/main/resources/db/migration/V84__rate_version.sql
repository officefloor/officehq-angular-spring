-- A rate is kept as versions: setting a rate for a grade and from-date that already has one keeps the
-- value it replaces as an older version. The rate row is always the newest version, the one in force;
-- version counts the versions it has had (1 for a rate set once).
ALTER TABLE rate ADD COLUMN version INT DEFAULT 1 NOT NULL;
CREATE TABLE rate_version (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    rate_id  BIGINT        NOT NULL REFERENCES rate (id),
    version  INT           NOT NULL,
    pct      DECIMAL(5, 2) NOT NULL,
    to_date  DATE
);
CREATE UNIQUE INDEX rate_version_rate ON rate_version (rate_id, version);
