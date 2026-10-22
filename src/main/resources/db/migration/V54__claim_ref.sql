-- A claim's external reference (null: none), and its key (lower case): a reference is used at most once on a sheet.
ALTER TABLE claim ADD COLUMN ref VARCHAR(200);
ALTER TABLE claim ADD COLUMN ref_key VARCHAR(200);
CREATE UNIQUE INDEX claim_sheet_ref ON claim (sheet_id, ref_key);
