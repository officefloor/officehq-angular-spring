-- The policy settings (rounding, floor, cap, terms, late-pct, clerk-limit) are versioned together: every change to
-- one of them is a new policy version, numbered from 1 per sheet in the order made, kept with the setting changed and
-- its from-date. A version with no from-date counts on every date.
CREATE TABLE policy_version (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id    VARCHAR(100) NOT NULL REFERENCES sheet (id),
    version_no  INT          NOT NULL,
    setting_key VARCHAR(20)  NOT NULL,
    from_date   DATE
);
CREATE UNIQUE INDEX policy_version_number ON policy_version (sheet_id, version_no);

-- The values already kept are versions in the order they were recorded; those kept from the start have no from-date.
INSERT INTO policy_version (sheet_id, version_no, setting_key, from_date)
    SELECT sheet_id, ROW_NUMBER() OVER (PARTITION BY sheet_id ORDER BY id), setting_key,
           CASE WHEN from_date = DATE '0001-01-01' THEN NULL ELSE from_date END
    FROM dated_setting
    WHERE setting_key IN ('rounding', 'floor', 'cap', 'terms', 'late-pct', 'clerk-limit');
