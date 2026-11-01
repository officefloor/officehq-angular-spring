-- A claim may carry an external reference, stored trimmed; none for a claim without one. It is unique on
-- the sheet without regard to case: ref_key is the lower-cased reference the uniqueness is kept on.
ALTER TABLE claim ADD COLUMN ref VARCHAR(200);
ALTER TABLE claim ADD COLUMN ref_key VARCHAR(200);
CREATE UNIQUE INDEX claim_sheet_ref ON claim (sheet_id, ref_key);
