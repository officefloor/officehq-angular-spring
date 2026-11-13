-- The policy settings (rounding, floor, cap, terms, late-pct, clerk-limit) are versioned together: every
-- change to one of them is a new policy version, numbered from 1 in the order made, keeping the from-date
-- the change took (none: counts always). The sheet's current version is the number of its versions; a claim
-- shows the number of versions whose from-date is on or before its date.
CREATE TABLE sheet_policy_version (
    sheet_id    VARCHAR(200) NOT NULL REFERENCES sheet (id),
    version_index INT         NOT NULL, -- the version number less 1
    setting_key VARCHAR(20)  NOT NULL,
    from_date   DATE,
    PRIMARY KEY (sheet_id, version_index)
);

-- The values already recorded become the versions, by from-date then key.
INSERT INTO sheet_policy_version (sheet_id, version_index, setting_key, from_date)
SELECT sheet_id, ROW_NUMBER() OVER (PARTITION BY sheet_id ORDER BY from_date, setting_key) - 1, setting_key, from_date
FROM sheet_setting
WHERE setting_key IN ('rounding', 'floor', 'cap', 'terms', 'late-pct', 'clerk-limit');
