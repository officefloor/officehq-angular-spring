-- The number of the claim a claim was split from; none for a claim that was not split off another.
ALTER TABLE claim ADD COLUMN split_from VARCHAR(220);
