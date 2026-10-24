-- A sheet's settings that carry a figure, each recorded from a date: the floor, the late percent, the
-- terms (the sheet's, and each grade's as terms-<grade>), the clerk limit and the cap. The value in
-- force on a date is the one with the latest from-date on or before it; before every from-date it is
-- the sheet's starting value (floor 1.00, late percent 0.00, terms 14; none for the others). A null
-- value records "none" from that date: no clerk limit, no cap, or a grade using the sheet's terms.
CREATE TABLE sheet_setting (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id      VARCHAR(100) NOT NULL REFERENCES sheet (id),
    setting_key   VARCHAR(20)  NOT NULL,
    from_date     DATE         NOT NULL,
    setting_value VARCHAR(20)
);
CREATE UNIQUE INDEX sheet_setting_sheet_key_from ON sheet_setting (sheet_id, setting_key, from_date);

-- The values already kept are in force from the beginning of time.
INSERT INTO sheet_setting (sheet_id, setting_key, from_date, setting_value)
    SELECT id, 'floor', DATE '0001-01-01', CAST(due_floor AS VARCHAR) FROM sheet WHERE due_floor <> 1.00;
INSERT INTO sheet_setting (sheet_id, setting_key, from_date, setting_value)
    SELECT id, 'late-pct', DATE '0001-01-01', CAST(late_pct AS VARCHAR) FROM sheet WHERE late_pct <> 0.00;
INSERT INTO sheet_setting (sheet_id, setting_key, from_date, setting_value)
    SELECT id, 'terms', DATE '0001-01-01', CAST(terms AS VARCHAR) FROM sheet WHERE terms <> 14;
INSERT INTO sheet_setting (sheet_id, setting_key, from_date, setting_value)
    SELECT id, 'clerk-limit', DATE '0001-01-01', CAST(clerk_limit AS VARCHAR) FROM sheet WHERE clerk_limit IS NOT NULL;
INSERT INTO sheet_setting (sheet_id, setting_key, from_date, setting_value)
    SELECT id, 'cap', DATE '0001-01-01', CAST(due_cap AS VARCHAR) FROM sheet WHERE due_cap IS NOT NULL;
INSERT INTO sheet_setting (sheet_id, setting_key, from_date, setting_value)
    SELECT sheet_id, 'terms-' || grade, DATE '0001-01-01', CAST(terms AS VARCHAR) FROM sheet_grade_terms;

DROP TABLE sheet_grade_terms;
ALTER TABLE sheet DROP COLUMN due_floor;
ALTER TABLE sheet DROP COLUMN late_pct;
ALTER TABLE sheet DROP COLUMN terms;
ALTER TABLE sheet DROP COLUMN clerk_limit;
ALTER TABLE sheet DROP COLUMN due_cap;
