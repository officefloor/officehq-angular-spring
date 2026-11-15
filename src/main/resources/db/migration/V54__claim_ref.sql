-- A claim's external reference: unique on its sheet, comparing without regard to case. NULL when
-- the claim has none.
ALTER TABLE claim ADD COLUMN ref VARCHAR(120);
