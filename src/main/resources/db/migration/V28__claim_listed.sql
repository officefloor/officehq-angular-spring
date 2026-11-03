-- A person's claims are listed newest first by their place in the list: a claim takes the next
-- place when it is added, and again when it is moved to another person, so a moved claim is
-- listed as its new person's newest whatever its date. Existing claims keep their order (by id).
CREATE SEQUENCE claim_listed_seq;
ALTER TABLE claim ADD COLUMN listed BIGINT;
UPDATE claim SET listed = id;
ALTER SEQUENCE claim_listed_seq RESTART WITH (SELECT COALESCE(MAX(id), 0) + 1 FROM claim);
ALTER TABLE claim ALTER COLUMN listed SET DEFAULT NEXT VALUE FOR claim_listed_seq;
ALTER TABLE claim ALTER COLUMN listed SET NOT NULL;
CREATE INDEX claim_sheet_listed ON claim (sheet_id, listed);
