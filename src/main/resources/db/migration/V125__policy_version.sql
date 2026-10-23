-- Policy versions: the policy settings (the rounding mode, the floor, the cap, the terms, the late
-- percent and the clerk limit) are versioned together. Every change to one of them is a new version,
-- numbered from 1 per sheet in the order made, with the setting it changed and the from-date it was
-- recorded from. A claim's version is the number of versions whose from-date is on or before its date;
-- a version with no from-date counts on every date. The dated values already recorded are taken as
-- versions made in from-date order.
CREATE TABLE sheet_policy_version (
    sheet_id       VARCHAR(64) NOT NULL REFERENCES sheet (id),
    version_number INT         NOT NULL,
    setting_key    VARCHAR(32) NOT NULL,
    from_date      DATE
);
CREATE UNIQUE INDEX sheet_policy_version_number ON sheet_policy_version (sheet_id, version_number);
INSERT INTO sheet_policy_version (sheet_id, version_number, setting_key, from_date)
    SELECT sheet_id, ROW_NUMBER() OVER (PARTITION BY sheet_id ORDER BY from_date, setting_key), setting_key, from_date
    FROM sheet_setting
    WHERE setting_key IN ('rounding', 'floor', 'cap', 'terms', 'late-pct', 'clerk-limit');
