-- Claims are listed newest first by the place they were listed at: when added, or when last moved to another
-- person, a claim takes the next place, so a moved claim is its new person's newest whatever its date.
CREATE SEQUENCE claim_listing;
ALTER TABLE claim ADD COLUMN listed BIGINT;
UPDATE claim SET listed = id;
ALTER SEQUENCE claim_listing RESTART WITH (SELECT COALESCE(MAX(id), 0) + 1 FROM claim);
ALTER TABLE claim ALTER COLUMN listed SET DEFAULT NEXT VALUE FOR claim_listing;
ALTER TABLE claim ALTER COLUMN listed SET NOT NULL;
CREATE INDEX claim_sheet_listed ON claim (sheet_id, listed);
