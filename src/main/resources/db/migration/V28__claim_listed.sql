-- Where a claim is listed among its person's claims, newest first: the highest on the sheet when it
-- is added, and again when it is reassigned to another person, so a moved claim is listed as its
-- new person's newest whatever its date.
ALTER TABLE claim ADD COLUMN listed BIGINT;
UPDATE claim SET listed = id;
ALTER TABLE claim ALTER COLUMN listed SET NOT NULL;
CREATE INDEX claim_sheet_listed ON claim (sheet_id, listed);
