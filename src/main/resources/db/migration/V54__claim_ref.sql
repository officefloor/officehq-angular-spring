-- A claim's external reference, unique on its sheet without regard to case; null for a claim with none.
ALTER TABLE claim ADD COLUMN ref VARCHAR(140);
CREATE INDEX claim_sheet_ref ON claim (sheet_id, ref);
