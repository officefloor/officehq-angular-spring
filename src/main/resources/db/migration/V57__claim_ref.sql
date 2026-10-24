-- A claim's external reference: null when it has none. Unique on the sheet without regard to case
-- (ref_key is the reference as compared).
ALTER TABLE claim ADD COLUMN ref VARCHAR(120);
ALTER TABLE claim ADD COLUMN ref_key VARCHAR(120);
CREATE UNIQUE INDEX claim_sheet_ref ON claim (sheet_id, ref_key);
