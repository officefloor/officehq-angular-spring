-- Claims are listed newest first by their place in the sheet's list, not by id: a claim moved to
-- another person takes the sheet's next place, so it is listed as that person's newest claim. Each
-- claim already stored keeps its place by id.
ALTER TABLE claim ADD COLUMN listed BIGINT;
UPDATE claim SET listed = id;
ALTER TABLE claim ALTER COLUMN listed SET NOT NULL;
CREATE INDEX claim_sheet_listed ON claim (sheet_id, listed);
