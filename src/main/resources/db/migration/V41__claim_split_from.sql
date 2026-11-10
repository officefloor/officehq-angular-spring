-- The number of the claim a claim was split off from; none for a claim not made by a split.
ALTER TABLE claim ADD COLUMN split_from VARCHAR(120);
