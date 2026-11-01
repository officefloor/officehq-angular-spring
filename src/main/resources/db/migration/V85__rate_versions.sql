-- A rate is kept as versions: setting a grade and from-date that already has a rate keeps the value
-- it replaces as an older version (its line in rate_log) and adds a new one. The rate row holds the
-- newest version, the one in force, and how many versions it has.
ALTER TABLE rate ADD COLUMN versions INTEGER NOT NULL DEFAULT 1;
UPDATE rate r SET versions = GREATEST(1, (SELECT COUNT(*) FROM rate_log l WHERE l.sheet_id = r.sheet_id
    AND l.grade = r.grade AND (l.from_date = r.from_date OR (l.from_date IS NULL AND r.from_date IS NULL))));
