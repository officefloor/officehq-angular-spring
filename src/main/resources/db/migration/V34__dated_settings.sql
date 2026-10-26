-- The settings that carry a figure are dated: each value is recorded from a from-date, and the one in force on a
-- date is the one with the latest from-date on or before it. Before every from-date the floor, the late percent and
-- the sheet's terms take the sheet's starting value (1.00, 0.00, 14); a grade's terms the sheet's terms; the clerk
-- limit and the cap none. The key is clerk-limit, floor, terms, terms-<grade>, late-pct or cap; the value is the
-- figure as text, or null for none (a clerk limit or a cap left blank).
CREATE TABLE sheet_setting (
    sheet_id    VARCHAR(100) NOT NULL REFERENCES sheet (id),
    setting_key VARCHAR(20)  NOT NULL,
    from_date   DATE         NOT NULL,
    setting_value VARCHAR(30),
    PRIMARY KEY (sheet_id, setting_key, from_date)
);

-- The values set until now stay in force from the beginning of time.
INSERT INTO sheet_setting (sheet_id, setting_key, from_date, setting_value)
    SELECT id, 'clerk-limit', DATE '0001-01-01', CAST(clerk_limit AS VARCHAR(30)) FROM sheet WHERE clerk_limit IS NOT NULL;
INSERT INTO sheet_setting (sheet_id, setting_key, from_date, setting_value)
    SELECT id, 'floor', DATE '0001-01-01', CAST(due_floor AS VARCHAR(30)) FROM sheet WHERE due_floor <> 1.00;
INSERT INTO sheet_setting (sheet_id, setting_key, from_date, setting_value)
    SELECT id, 'terms', DATE '0001-01-01', CAST(terms AS VARCHAR(30)) FROM sheet WHERE terms <> 14;
INSERT INTO sheet_setting (sheet_id, setting_key, from_date, setting_value)
    SELECT id, 'late-pct', DATE '0001-01-01', CAST(late_pct AS VARCHAR(30)) FROM sheet WHERE late_pct <> 0.00;
INSERT INTO sheet_setting (sheet_id, setting_key, from_date, setting_value)
    SELECT id, 'cap', DATE '0001-01-01', CAST(due_cap AS VARCHAR(30)) FROM sheet WHERE due_cap IS NOT NULL;
INSERT INTO sheet_setting (sheet_id, setting_key, from_date, setting_value)
    SELECT sheet_id, 'terms-' || grade, DATE '0001-01-01', CAST(terms AS VARCHAR(30)) FROM grade_terms;

DROP TABLE grade_terms;
ALTER TABLE sheet DROP COLUMN clerk_limit;
ALTER TABLE sheet DROP COLUMN due_floor;
ALTER TABLE sheet DROP COLUMN terms;
ALTER TABLE sheet DROP COLUMN late_pct;
ALTER TABLE sheet DROP COLUMN due_cap;
