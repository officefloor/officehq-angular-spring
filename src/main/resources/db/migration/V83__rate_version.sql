-- A rate is kept as versions: setting a grade's rate for a from-date it already has keeps the value it replaces as an
-- older version and makes the new one the newest. The rate row holds the newest version (the one in force) and its
-- number, counting from 1; rate_version holds each older one.
ALTER TABLE rate ADD COLUMN version INTEGER DEFAULT 1 NOT NULL;
CREATE TABLE rate_version (
    id      BIGINT AUTO_INCREMENT PRIMARY KEY,
    rate_id BIGINT        NOT NULL REFERENCES rate (id),
    version INTEGER       NOT NULL,
    pct     DECIMAL(7, 2) NOT NULL,
    to_date DATE
);
CREATE UNIQUE INDEX rate_version_rate ON rate_version (rate_id, version);
