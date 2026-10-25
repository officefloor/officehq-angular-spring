-- A sheet's policy versions: the policy settings (the rounding mode, the floor, the cap, the terms, the
-- late percent and the clerk limit) are versioned together, every change to one of them a new version,
-- numbered from 1 on the sheet in the order made. Each keeps the setting changed, its value and the date
-- it is in force from; a null from-date counts on every date. A claim's version is the number of the
-- sheet's versions in force on its date.
CREATE TABLE policy_version (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id      VARCHAR(100) NOT NULL REFERENCES sheet (id),
    number        INT          NOT NULL,
    from_date     DATE,
    setting_key   VARCHAR(20)  NOT NULL,
    setting_value VARCHAR(20)
);
CREATE UNIQUE INDEX policy_version_sheet_number ON policy_version (sheet_id, number);

-- The values already recorded are versions in the order they were recorded; those in force from the
-- beginning of time have no from-date.
INSERT INTO policy_version (sheet_id, number, from_date, setting_key, setting_value)
    SELECT sheet_id, ROW_NUMBER() OVER (PARTITION BY sheet_id ORDER BY id),
           CASE WHEN from_date = DATE '0001-01-01' THEN NULL ELSE from_date END, setting_key, setting_value
    FROM sheet_setting
    WHERE setting_key IN ('rounding', 'floor', 'cap', 'terms', 'late-pct', 'clerk-limit');
