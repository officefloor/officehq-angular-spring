-- The policy settings (rounding, floor, cap, terms, late-pct, clerk-limit) are versioned together: every change to
-- one of them is a new policy version, numbered from 1 in the order made (version n at version_index n - 1), kept
-- with the setting changed and the from-date it was changed from (none: it counts always).
CREATE TABLE sheet_policy_version (
    sheet_id      VARCHAR(100) NOT NULL REFERENCES sheet (id),
    version_index INT          NOT NULL,
    setting_key   VARCHAR(20)  NOT NULL,
    from_date     DATE,
    PRIMARY KEY (sheet_id, version_index)
);

-- The values recorded until now are a version each, in the order of their from-dates.
INSERT INTO sheet_policy_version (sheet_id, version_index, setting_key, from_date)
    SELECT sheet_id,
           ROW_NUMBER() OVER (PARTITION BY sheet_id ORDER BY from_date NULLS FIRST, setting_key) - 1,
           setting_key, from_date
    FROM sheet_setting
    WHERE setting_key IN ('rounding', 'floor', 'cap', 'terms', 'late-pct', 'clerk-limit');
