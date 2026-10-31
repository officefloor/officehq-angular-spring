-- Settings that carry a figure are dated: each save records its value from a from-date, keeping the values
-- already recorded (one per key and from-date). A value is in force from its from-date until the next one's;
-- before every from-date the sheet's starting value applies (floor 1.00, late percent 0.00, terms 14, none for
-- the others). A value with no from-date applies from the beginning of time. A null value means none (no clerk
-- limit, no cap, or a grade on the sheet's terms).
-- Keys: clerk-limit, floor, terms, terms-A, terms-B, terms-C, late-pct, cap.
CREATE TABLE sheet_setting (
    sheet_id    VARCHAR(100) NOT NULL REFERENCES sheet (id),
    setting_key VARCHAR(20)  NOT NULL,
    from_date   DATE,
    setting_value VARCHAR(20),
    CONSTRAINT sheet_setting_key_from UNIQUE NULLS NOT DISTINCT (sheet_id, setting_key, from_date)
);

-- The values set until now stay in force from the beginning of time.
INSERT INTO sheet_setting (sheet_id, setting_key, from_date, setting_value)
    SELECT id, 'clerk-limit', NULL, CAST(clerk_limit AS VARCHAR) FROM sheet WHERE clerk_limit IS NOT NULL;
INSERT INTO sheet_setting (sheet_id, setting_key, from_date, setting_value)
    SELECT id, 'floor', NULL, CAST(floor AS VARCHAR) FROM sheet WHERE floor <> 1.00;
INSERT INTO sheet_setting (sheet_id, setting_key, from_date, setting_value)
    SELECT id, 'terms', NULL, CAST(terms AS VARCHAR) FROM sheet WHERE terms <> 14;
INSERT INTO sheet_setting (sheet_id, setting_key, from_date, setting_value)
    SELECT id, 'late-pct', NULL, CAST(late_pct AS VARCHAR) FROM sheet WHERE late_pct <> 0.00;
INSERT INTO sheet_setting (sheet_id, setting_key, from_date, setting_value)
    SELECT id, 'cap', NULL, CAST(cap AS VARCHAR) FROM sheet WHERE cap IS NOT NULL;
INSERT INTO sheet_setting (sheet_id, setting_key, from_date, setting_value)
    SELECT sheet_id, 'terms-' || grade, NULL, CAST(terms AS VARCHAR) FROM sheet_grade_terms;

DROP TABLE sheet_grade_terms;
ALTER TABLE sheet DROP COLUMN clerk_limit;
ALTER TABLE sheet DROP COLUMN floor;
ALTER TABLE sheet DROP COLUMN terms;
ALTER TABLE sheet DROP COLUMN late_pct;
ALTER TABLE sheet DROP COLUMN cap;
