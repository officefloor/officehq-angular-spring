-- A claim may carry an external reference, unique on its sheet without regard to case: ref as
-- typed (trimmed), ref_key its lower-case form; both null when the claim has none.
ALTER TABLE claim ADD COLUMN ref VARCHAR(255);
ALTER TABLE claim ADD COLUMN ref_key VARCHAR(255);
ALTER TABLE claim ADD CONSTRAINT claim_ref_unique UNIQUE (sheet_id, ref_key);
