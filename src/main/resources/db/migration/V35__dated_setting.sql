-- Settings that carry a figure are dated: each value is recorded from a from-date and is in force
-- from it until the next from-date of the same key. Before every from-date a key takes the sheet's
-- starting value (floor 1.00, late percent 0.00, terms 14 days; none for the rest). A blank value
-- (no clerk limit, no cap, a grade without terms of its own) is NULL.
CREATE TABLE sheet_setting (
    sheet_id      VARCHAR(100) NOT NULL REFERENCES sheet (id),
    setting_key   VARCHAR(20)  NOT NULL,
    from_date     DATE         NOT NULL,
    setting_value VARCHAR(20),
    PRIMARY KEY (sheet_id, setting_key, from_date)
);
-- The values sheets held before this change stay in force for every date.
INSERT INTO sheet_setting SELECT id, 'floor', DATE '1900-01-01', CAST(due_floor AS VARCHAR) FROM sheet WHERE due_floor <> 1.00;
INSERT INTO sheet_setting SELECT id, 'terms', DATE '1900-01-01', CAST(terms AS VARCHAR) FROM sheet WHERE terms <> 14;
INSERT INTO sheet_setting SELECT id, 'late-pct', DATE '1900-01-01', CAST(late_pct AS VARCHAR) FROM sheet WHERE late_pct <> 0;
INSERT INTO sheet_setting SELECT id, 'clerk-limit', DATE '1900-01-01', CAST(clerk_limit AS VARCHAR) FROM sheet WHERE clerk_limit IS NOT NULL;
INSERT INTO sheet_setting SELECT id, 'cap', DATE '1900-01-01', CAST(due_cap AS VARCHAR) FROM sheet WHERE due_cap IS NOT NULL;
INSERT INTO sheet_setting SELECT sheet_id, 'terms-' || grade, DATE '1900-01-01', CAST(terms AS VARCHAR) FROM sheet_grade_terms;
DROP TABLE sheet_grade_terms;
ALTER TABLE sheet DROP COLUMN due_floor;
ALTER TABLE sheet DROP COLUMN terms;
ALTER TABLE sheet DROP COLUMN late_pct;
ALTER TABLE sheet DROP COLUMN clerk_limit;
ALTER TABLE sheet DROP COLUMN due_cap;
