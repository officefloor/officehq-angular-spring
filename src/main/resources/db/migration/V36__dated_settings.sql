-- A sheet's settings that carry a figure are dated: each value is in force from its from-date until the next
-- one of the same key; before every from-date the setting has the value a sheet starts with. A value already
-- set has no from-date and applies from the beginning of time. A value is none (null) where the setting allows
-- it (no clerk limit, no cap, a grade using the sheet's terms).
CREATE TABLE sheet_setting_from (
    sheet_id      VARCHAR(100)   NOT NULL REFERENCES sheet (id),
    setting_key   VARCHAR(30)    NOT NULL,
    setting_from  DATE,
    setting_value DECIMAL(12, 2) CHECK (setting_value >= 0)
);
CREATE INDEX sheet_setting_from_sheet ON sheet_setting_from (sheet_id);
INSERT INTO sheet_setting_from (sheet_id, setting_key, setting_from, setting_value)
    SELECT id, 'floor', NULL, due_floor FROM sheet WHERE due_floor <> 1.00;
INSERT INTO sheet_setting_from (sheet_id, setting_key, setting_from, setting_value)
    SELECT id, 'late-pct', NULL, late_pct FROM sheet WHERE late_pct <> 0.00;
INSERT INTO sheet_setting_from (sheet_id, setting_key, setting_from, setting_value)
    SELECT id, 'terms', NULL, terms_days FROM sheet WHERE terms_days <> 14;
INSERT INTO sheet_setting_from (sheet_id, setting_key, setting_from, setting_value)
    SELECT id, 'clerk-limit', NULL, clerk_limit FROM sheet WHERE clerk_limit IS NOT NULL;
INSERT INTO sheet_setting_from (sheet_id, setting_key, setting_from, setting_value)
    SELECT id, 'cap', NULL, cap FROM sheet WHERE cap IS NOT NULL;
INSERT INTO sheet_setting_from (sheet_id, setting_key, setting_from, setting_value)
    SELECT sheet_id, 'terms-' || grade, NULL, terms_days FROM sheet_grade_terms;
DROP TABLE sheet_grade_terms;
ALTER TABLE sheet DROP COLUMN due_floor;
ALTER TABLE sheet DROP COLUMN late_pct;
ALTER TABLE sheet DROP COLUMN terms_days;
ALTER TABLE sheet DROP COLUMN clerk_limit;
ALTER TABLE sheet DROP COLUMN cap;
