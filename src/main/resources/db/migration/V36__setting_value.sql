-- Dated settings: a figure a setting takes from a date on (the floor, the late percent, the terms of
-- the sheet and of each grade, the clerk limit and the cap). The value in force on a date is the one
-- with the latest from-date on or before it; before every from-date the sheet's starting value
-- (floor 1.00, late percent 0.00, terms 14; none for the others). A null value is blank (no limit,
-- no cap).
CREATE TABLE setting_value (
    sheet_id    VARCHAR(100)   NOT NULL,
    setting_key VARCHAR(20)    NOT NULL,
    from_date   DATE           NOT NULL,
    setting     DECIMAL(14, 2),
    PRIMARY KEY (sheet_id, setting_key, from_date)
);
-- The values already set carry over from the earliest date, so they stay in force everywhere.
INSERT INTO setting_value (sheet_id, setting_key, from_date, setting)
    SELECT id, 'floor', DATE '0001-01-01', due_floor FROM sheet WHERE due_floor <> 1.00;
INSERT INTO setting_value (sheet_id, setting_key, from_date, setting)
    SELECT id, 'late-pct', DATE '0001-01-01', late_pct FROM sheet WHERE late_pct <> 0.00;
INSERT INTO setting_value (sheet_id, setting_key, from_date, setting)
    SELECT id, 'terms', DATE '0001-01-01', terms FROM sheet WHERE terms <> 14;
INSERT INTO setting_value (sheet_id, setting_key, from_date, setting)
    SELECT id, 'clerk-limit', DATE '0001-01-01', clerk_limit FROM sheet WHERE clerk_limit IS NOT NULL;
INSERT INTO setting_value (sheet_id, setting_key, from_date, setting)
    SELECT id, 'cap', DATE '0001-01-01', cap FROM sheet WHERE cap IS NOT NULL;
INSERT INTO setting_value (sheet_id, setting_key, from_date, setting)
    SELECT sheet_id, 'terms-' || grade, DATE '0001-01-01', terms FROM grade_terms;
ALTER TABLE sheet DROP COLUMN due_floor;
ALTER TABLE sheet DROP COLUMN late_pct;
ALTER TABLE sheet DROP COLUMN terms;
ALTER TABLE sheet DROP COLUMN clerk_limit;
ALTER TABLE sheet DROP COLUMN cap;
DROP TABLE grade_terms;
