-- A claim's external reference: null when none. No two claims of a sheet share one, comparing
-- without regard to case (ref_key is the reference in upper case; nulls never clash).
ALTER TABLE claim ADD COLUMN ref VARCHAR(120);
ALTER TABLE claim ADD COLUMN ref_key VARCHAR(120) GENERATED ALWAYS AS (UPPER(ref));
CREATE UNIQUE INDEX claim_sheet_ref ON claim (sheet_id, ref_key);
