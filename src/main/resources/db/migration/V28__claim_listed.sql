-- Where a claim is listed: claims are listed newest first by this, not by id, so a claim moved to
-- another person is listed as that person's newest. A new claim takes the next value, and so does a
-- claim when it is moved.
CREATE SEQUENCE claim_listed_seq;
ALTER TABLE claim ADD COLUMN listed BIGINT DEFAULT NEXT VALUE FOR claim_listed_seq NOT NULL;
CREATE INDEX claim_sheet_listed ON claim (sheet_id, listed);
