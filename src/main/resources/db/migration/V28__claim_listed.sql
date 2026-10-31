-- A person's claims are listed newest first by when they were listed under that person: a claim is
-- listed when it is added and again when it is reassigned to another person, so a moved claim is its
-- new person's newest whatever its date or id. Existing claims keep their order (by id).
ALTER TABLE claim ADD COLUMN listed BIGINT;
UPDATE claim SET listed = id;
ALTER TABLE claim ALTER COLUMN listed SET NOT NULL;
CREATE INDEX claim_sheet_listed ON claim (sheet_id, listed);
