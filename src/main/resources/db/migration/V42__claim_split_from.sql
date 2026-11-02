-- The number of the claim a claim was split off; null for a claim that was not split off one.
ALTER TABLE claim ADD COLUMN split_from VARCHAR(120);
