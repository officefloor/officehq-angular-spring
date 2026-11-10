-- The sheet's settings that carry a figure, each recorded from a from-date (none: from the beginning
-- of time): the floor, the late percent, the terms (key terms, and terms-<grade> for a grade's own),
-- the clerk limit and the cap. The value in force on a date is the one with the latest from-date on
-- or before it; before every from-date a key has its starting value (floor 1.00, late percent 0.00,
-- terms 14; a grade's terms, the clerk limit and the cap none). A null value: none (no limit, no cap).
CREATE TABLE sheet_setting (
    sheet_id    VARCHAR(100)   NOT NULL REFERENCES sheet (id),
    setting_key VARCHAR(20)    NOT NULL,
    from_date   DATE,
    amount      DECIMAL(14, 2) CHECK (amount >= 0)
);
CREATE INDEX sheet_setting_sheet ON sheet_setting (sheet_id);
-- The settings each sheet has so far apply from the beginning of time.
INSERT INTO sheet_setting (sheet_id, setting_key, from_date, amount)
    SELECT id, 'floor', NULL, due_floor FROM sheet WHERE due_floor <> 1.00;
INSERT INTO sheet_setting (sheet_id, setting_key, from_date, amount)
    SELECT id, 'late-pct', NULL, late_pct FROM sheet WHERE late_pct <> 0.00;
INSERT INTO sheet_setting (sheet_id, setting_key, from_date, amount)
    SELECT id, 'terms', NULL, terms FROM sheet WHERE terms <> 14;
INSERT INTO sheet_setting (sheet_id, setting_key, from_date, amount)
    SELECT id, 'clerk-limit', NULL, clerk_limit FROM sheet WHERE clerk_limit IS NOT NULL;
INSERT INTO sheet_setting (sheet_id, setting_key, from_date, amount)
    SELECT id, 'cap', NULL, due_cap FROM sheet WHERE due_cap IS NOT NULL;
INSERT INTO sheet_setting (sheet_id, setting_key, from_date, amount)
    SELECT sheet_id, 'terms-' || grade, NULL, terms FROM sheet_grade_terms;
DROP TABLE sheet_grade_terms;
ALTER TABLE sheet DROP COLUMN due_floor;
ALTER TABLE sheet DROP COLUMN late_pct;
ALTER TABLE sheet DROP COLUMN terms;
ALTER TABLE sheet DROP COLUMN clerk_limit;
ALTER TABLE sheet DROP COLUMN due_cap;
