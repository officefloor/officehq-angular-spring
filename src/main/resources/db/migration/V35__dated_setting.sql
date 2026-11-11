-- Settings that carry a figure are dated: each value is kept from its from-date. The value in force on a date is the
-- one with the latest from-date on or before it; before every from-date a key has the sheet's starting value (floor
-- 1.00, late-pct 0.00, terms 14; none for the clerk limit, the cap and a grade's terms). Keys: clerk-limit, floor,
-- terms, terms-A, terms-B, terms-C, late-pct, cap. A null value is "none" (no clerk limit, no cap).
CREATE TABLE dated_setting (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id      VARCHAR(100) NOT NULL REFERENCES sheet (id),
    setting_key   VARCHAR(20)  NOT NULL,
    from_date     DATE         NOT NULL,
    setting_value VARCHAR(20)
);
CREATE UNIQUE INDEX dated_setting_key_from ON dated_setting (sheet_id, setting_key, from_date);

-- The values already kept hold from the start.
INSERT INTO dated_setting (sheet_id, setting_key, from_date, setting_value)
    SELECT id, 'clerk-limit', DATE '0001-01-01', CAST(clerk_limit AS VARCHAR(20)) FROM sheet WHERE clerk_limit IS NOT NULL;
INSERT INTO dated_setting (sheet_id, setting_key, from_date, setting_value)
    SELECT id, 'floor', DATE '0001-01-01', CAST(due_floor AS VARCHAR(20)) FROM sheet WHERE due_floor <> 1.00;
INSERT INTO dated_setting (sheet_id, setting_key, from_date, setting_value)
    SELECT id, 'terms', DATE '0001-01-01', CAST(terms AS VARCHAR(20)) FROM sheet WHERE terms <> 14;
INSERT INTO dated_setting (sheet_id, setting_key, from_date, setting_value)
    SELECT id, 'late-pct', DATE '0001-01-01', CAST(late_pct AS VARCHAR(20)) FROM sheet WHERE late_pct <> 0.00;
INSERT INTO dated_setting (sheet_id, setting_key, from_date, setting_value)
    SELECT id, 'cap', DATE '0001-01-01', CAST(due_cap AS VARCHAR(20)) FROM sheet WHERE due_cap IS NOT NULL;
INSERT INTO dated_setting (sheet_id, setting_key, from_date, setting_value)
    SELECT sheet_id, 'terms-' || grade, DATE '0001-01-01', CAST(terms AS VARCHAR(20)) FROM grade_terms;

DROP TABLE grade_terms;
ALTER TABLE sheet DROP COLUMN clerk_limit;
ALTER TABLE sheet DROP COLUMN due_floor;
ALTER TABLE sheet DROP COLUMN terms;
ALTER TABLE sheet DROP COLUMN late_pct;
ALTER TABLE sheet DROP COLUMN due_cap;
