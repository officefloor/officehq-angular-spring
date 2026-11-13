-- A sheet's settings that carry a figure are dated: each value recorded is in force from its from-date
-- until the next one's. Keys: floor, late-pct, terms, terms-<grade>, clerk-limit, cap. A value is null
-- only for the clerk limit or the cap, where it means none. Before a key's first from-date the sheet's
-- starting value applies (floor 1.00, late percent 0.00, terms 14; a grade's terms are the sheet's; no
-- clerk limit and no cap).
CREATE TABLE sheet_setting_value (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(200) NOT NULL REFERENCES sheet (id),
    setting_key VARCHAR(20) NOT NULL,
    from_date DATE NOT NULL,
    setting_value DECIMAL(12, 2),
    CHECK (setting_value IS NULL OR setting_value >= 0)
);
CREATE UNIQUE INDEX sheet_setting_value_sheet_key_from ON sheet_setting_value (sheet_id, setting_key, from_date);

-- The values set before settings were dated were in force on every date.
INSERT INTO sheet_setting_value (sheet_id, setting_key, from_date, setting_value)
    SELECT id, 'floor', DATE '0001-01-01', due_floor FROM sheet WHERE due_floor <> 1.00;
INSERT INTO sheet_setting_value (sheet_id, setting_key, from_date, setting_value)
    SELECT id, 'late-pct', DATE '0001-01-01', late_pct FROM sheet WHERE late_pct <> 0.00;
INSERT INTO sheet_setting_value (sheet_id, setting_key, from_date, setting_value)
    SELECT id, 'terms', DATE '0001-01-01', terms FROM sheet WHERE terms <> 14;
INSERT INTO sheet_setting_value (sheet_id, setting_key, from_date, setting_value)
    SELECT id, 'clerk-limit', DATE '0001-01-01', clerk_limit FROM sheet WHERE clerk_limit IS NOT NULL;
INSERT INTO sheet_setting_value (sheet_id, setting_key, from_date, setting_value)
    SELECT id, 'cap', DATE '0001-01-01', due_cap FROM sheet WHERE due_cap IS NOT NULL;
INSERT INTO sheet_setting_value (sheet_id, setting_key, from_date, setting_value)
    SELECT sheet_id, 'terms-' || grade, DATE '0001-01-01', terms FROM sheet_grade_terms;

ALTER TABLE sheet DROP COLUMN due_floor;
ALTER TABLE sheet DROP COLUMN late_pct;
ALTER TABLE sheet DROP COLUMN terms;
ALTER TABLE sheet DROP COLUMN clerk_limit;
ALTER TABLE sheet DROP COLUMN due_cap;
DROP TABLE sheet_grade_terms;
