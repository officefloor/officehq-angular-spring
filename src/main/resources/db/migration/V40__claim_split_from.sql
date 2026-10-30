-- The number (as shown) of the claim a claim was split off; null for any claim not split off another.
ALTER TABLE claim ADD COLUMN split_from VARCHAR(120);
