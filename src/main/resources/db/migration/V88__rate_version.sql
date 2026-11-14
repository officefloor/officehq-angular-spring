-- A rate is kept as versions: setting a rate for a grade and from-date that already has one keeps
-- the value it replaces as an older version and makes the new one the next version, the one in
-- force. version is the rate's version count (1 for a rate never replaced).
ALTER TABLE rate ADD COLUMN version INT NOT NULL DEFAULT 1;
ALTER TABLE rate_history ADD COLUMN version INT NOT NULL DEFAULT 1;

-- Each older version of a rate: the value and to-date it held as that version, and the sheet's
-- today and the user when it was replaced.
CREATE TABLE rate_version (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    rate_id BIGINT NOT NULL REFERENCES rate (id),
    version INT NOT NULL,
    rate DECIMAL(7, 2) NOT NULL,
    to_date DATE,
    replaced_on DATE,
    replaced_by VARCHAR(200) NOT NULL
);
CREATE UNIQUE INDEX rate_version_rate ON rate_version (rate_id, version);
