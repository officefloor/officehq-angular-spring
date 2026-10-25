-- A claim may carry an external reference, unique on its sheet without regard to case; null when it has none.
ALTER TABLE claim ADD COLUMN ref VARCHAR(220);
CREATE INDEX claim_ref_key ON claim (sheet_id, ref);
