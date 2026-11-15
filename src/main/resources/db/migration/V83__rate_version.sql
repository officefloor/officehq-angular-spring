-- A rate is kept as versions: setting a rate for a grade and from-date that already has one keeps
-- the value it replaces as an older version and makes the new value the newest. The rate row holds
-- the newest version (the one in force) and its version number; rates kept before this change are
-- their version 1.
ALTER TABLE rate ADD COLUMN version INTEGER DEFAULT 1 NOT NULL;
-- The older versions of a rate, each as it stood before the version after it replaced it.
CREATE TABLE rate_version (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    rate_id  BIGINT        NOT NULL REFERENCES rate (id),
    version  INTEGER       NOT NULL,
    pct      DECIMAL(5, 2) NOT NULL,
    to_date  DATE
);
CREATE UNIQUE INDEX rate_version_rate ON rate_version (rate_id, version);
