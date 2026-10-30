-- A rate is kept as versions: the rate row holds the newest (the one in force) and its version number,
-- counting from 1; each value it replaced is kept in rate_version with the version number it had.
ALTER TABLE rate ADD COLUMN version INT NOT NULL DEFAULT 1;
CREATE TABLE rate_version (
    id      BIGINT AUTO_INCREMENT PRIMARY KEY,
    rate_id BIGINT        NOT NULL REFERENCES rate (id),
    version INT           NOT NULL,
    pct     DECIMAL(5, 2) NOT NULL,
    to_date DATE,
    CONSTRAINT rate_version_unique UNIQUE (rate_id, version)
);
