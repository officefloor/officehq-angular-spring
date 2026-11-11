-- Where a claim stands in its person's list, newest first: the sheet's next list place when it is added, and again
-- when it is reassigned to another person, so it is listed as that person's newest claim whatever its date.
ALTER TABLE sheet ADD COLUMN last_listed BIGINT DEFAULT 0 NOT NULL;
ALTER TABLE claim ADD COLUMN listed BIGINT;
UPDATE claim SET listed = id;
ALTER TABLE claim ALTER COLUMN listed SET NOT NULL;
UPDATE sheet SET last_listed = COALESCE((SELECT MAX(c.listed) FROM claim c WHERE c.sheet_id = sheet.id), 0);
