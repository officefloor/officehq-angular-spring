-- The number of the claim a claim was split from: set on the new OPEN claim a split makes (the
-- original keeps none).
ALTER TABLE claim ADD COLUMN split_from VARCHAR(120);
