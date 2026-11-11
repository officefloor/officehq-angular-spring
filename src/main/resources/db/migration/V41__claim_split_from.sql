-- The number of the claim a claim was split off; null unless it was split off another.
ALTER TABLE claim ADD COLUMN split_from VARCHAR(255);
