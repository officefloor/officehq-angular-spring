-- A setting that carries a figure (the clerk limit, the floor, the terms and each grade's own terms, the
-- late percent, the cap) is dated: each value is in force from its from-date, a value with no from-date
-- from the beginning of time. A value is kept as written; null is a value saved blank (no limit, the
-- starting value, or the sheet's terms). A key has at most one value per from-date.
CREATE TABLE sheet_setting (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id      VARCHAR(100) NOT NULL REFERENCES sheet (id),
    setting_key   VARCHAR(40)  NOT NULL,
    from_date     DATE,
    setting_value VARCHAR(40),
    from_key      DATE GENERATED ALWAYS AS (COALESCE(from_date, DATE '0001-01-01')),
    CONSTRAINT sheet_setting_key_from_unique UNIQUE (sheet_id, setting_key, from_key)
);
-- The values a sheet already has hold from the beginning of time.
INSERT INTO sheet_setting (sheet_id, setting_key, setting_value)
    SELECT id, 'clerk-limit', CAST(clerk_limit AS VARCHAR(40)) FROM sheet WHERE clerk_limit IS NOT NULL;
INSERT INTO sheet_setting (sheet_id, setting_key, setting_value)
    SELECT id, 'floor', CAST(due_floor AS VARCHAR(40)) FROM sheet WHERE due_floor <> 1.00;
INSERT INTO sheet_setting (sheet_id, setting_key, setting_value)
    SELECT id, 'terms', CAST(terms AS VARCHAR(40)) FROM sheet WHERE terms <> 14;
INSERT INTO sheet_setting (sheet_id, setting_key, setting_value)
    SELECT id, 'terms-A', CAST(terms_a AS VARCHAR(40)) FROM sheet WHERE terms_a IS NOT NULL;
INSERT INTO sheet_setting (sheet_id, setting_key, setting_value)
    SELECT id, 'terms-B', CAST(terms_b AS VARCHAR(40)) FROM sheet WHERE terms_b IS NOT NULL;
INSERT INTO sheet_setting (sheet_id, setting_key, setting_value)
    SELECT id, 'terms-C', CAST(terms_c AS VARCHAR(40)) FROM sheet WHERE terms_c IS NOT NULL;
INSERT INTO sheet_setting (sheet_id, setting_key, setting_value)
    SELECT id, 'late-pct', CAST(late_pct AS VARCHAR(40)) FROM sheet WHERE late_pct <> 0.00;
INSERT INTO sheet_setting (sheet_id, setting_key, setting_value)
    SELECT id, 'cap', CAST(due_cap AS VARCHAR(40)) FROM sheet WHERE due_cap IS NOT NULL;
ALTER TABLE sheet DROP COLUMN clerk_limit;
ALTER TABLE sheet DROP COLUMN due_floor;
ALTER TABLE sheet DROP COLUMN terms;
ALTER TABLE sheet DROP COLUMN terms_a;
ALTER TABLE sheet DROP COLUMN terms_b;
ALTER TABLE sheet DROP COLUMN terms_c;
ALTER TABLE sheet DROP COLUMN late_pct;
ALTER TABLE sheet DROP COLUMN due_cap;
