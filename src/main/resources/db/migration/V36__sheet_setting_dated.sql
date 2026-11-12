-- Settings that carry a figure are dated: each save records its value from a from-date, and the value in force
-- on a date is the one with the latest from-date on or before it. Keys: floor, late-pct, terms, terms-<grade>,
-- clerk-limit, cap. A value of none (blank) means no limit, no cap, or a grade using the sheet's terms.
-- Before every from-date the floor is 1.00, the late percent 0.00 and the terms 14; the others are none.
CREATE TABLE sheet_setting (
    sheet_id      VARCHAR(200) NOT NULL REFERENCES sheet (id),
    setting_key   VARCHAR(20)  NOT NULL,
    from_date     DATE         NOT NULL,
    setting_value VARCHAR(20),
    PRIMARY KEY (sheet_id, setting_key, from_date)
);

-- The values a sheet already holds keep applying on every date, as they did before they were dated.
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
