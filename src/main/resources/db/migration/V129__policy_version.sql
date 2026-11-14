-- The sheet's policy versions: every change to the rounding mode, the floor, the cap, the terms, the
-- late percent or the clerk limit is a new version, numbered from 1 in the order made, in force from
-- its from-date (null: always), made while the sheet's today was its made-on.
CREATE TABLE policy_version (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(200) NOT NULL REFERENCES sheet (id),
    version_number INT NOT NULL,
    from_date DATE,
    made_on DATE NOT NULL
);
CREATE UNIQUE INDEX policy_version_number ON policy_version (sheet_id, version_number);

-- The changes already kept become the versions they were, in the order made.
INSERT INTO policy_version (sheet_id, version_number, from_date, made_on)
SELECT sheet_id, ROW_NUMBER() OVER (PARTITION BY sheet_id ORDER BY id), from_date, made_on
FROM sheet_setting_value_history
WHERE setting_key IN ('rounding', 'floor', 'cap', 'terms', 'late-pct', 'clerk-limit');
