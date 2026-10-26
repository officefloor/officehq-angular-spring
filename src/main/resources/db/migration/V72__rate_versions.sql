-- A rate is kept as versions: setting a rate for a grade and from-date that already has one keeps the
-- value it replaces as an older version (its rate_change row) and adds a new one. The rate row holds the
-- newest version, which is the one in force, and how many versions it has had (version: 1 for the first).
-- Each rate_change row carries the version number it made.
ALTER TABLE rate_change ADD COLUMN version INT;
UPDATE rate_change c SET version = (
    SELECT COUNT(*) FROM rate_change o
    WHERE o.sheet_id = c.sheet_id AND o.grade = c.grade
      AND COALESCE(o.from_date, DATE '0001-01-01') = COALESCE(c.from_date, DATE '0001-01-01')
      AND o.id <= c.id);

ALTER TABLE rate ADD COLUMN version INT DEFAULT 1 NOT NULL;
UPDATE rate r SET version = (
    SELECT GREATEST(COUNT(*), 1) FROM rate_change c
    WHERE c.sheet_id = r.sheet_id AND c.grade = r.grade
      AND COALESCE(c.from_date, DATE '0001-01-01') = r.from_key);
