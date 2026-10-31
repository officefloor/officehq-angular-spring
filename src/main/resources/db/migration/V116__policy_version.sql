-- The policy settings (the rounding mode, the floor, the cap, the terms, the late percent and the clerk limit)
-- are versioned together: every change to one of them is a new version of the sheet's policy, numbered from 1
-- in the order made, keeping the key, from-date and value it saved (null: saved blank). A version with no
-- from-date is in force from the beginning of time.
CREATE TABLE policy_version (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id      VARCHAR(100) NOT NULL REFERENCES sheet (id),
    version       INT          NOT NULL,
    setting_key   VARCHAR(40)  NOT NULL,
    from_date     DATE,
    setting_value VARCHAR(40),
    CONSTRAINT policy_version_unique UNIQUE (sheet_id, version)
);
-- The policy values a sheet already has are its versions so far, in the order they were saved.
INSERT INTO policy_version (sheet_id, version, setting_key, from_date, setting_value)
    SELECT sheet_id, ROW_NUMBER() OVER (PARTITION BY sheet_id ORDER BY id), setting_key, from_date, setting_value
    FROM sheet_setting
    WHERE setting_key IN ('rounding', 'floor', 'cap', 'terms', 'late-pct', 'clerk-limit');
