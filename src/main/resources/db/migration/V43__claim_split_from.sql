-- A claim split off another carries in split_from the number of the claim it was split from; null on
-- any other claim.
ALTER TABLE claim ADD COLUMN split_from VARCHAR(120);
