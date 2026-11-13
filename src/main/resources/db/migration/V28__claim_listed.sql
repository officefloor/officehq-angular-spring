-- A claim is listed under its person newest first by when it was listed there: when it was added,
-- or when it was last reassigned to them (a reassigned claim is its new person's newest).
CREATE SEQUENCE claim_listed_seq;
ALTER TABLE claim ADD COLUMN listed BIGINT DEFAULT NEXT VALUE FOR claim_listed_seq NOT NULL;
CREATE INDEX claim_sheet_listed ON claim (sheet_id, listed);
