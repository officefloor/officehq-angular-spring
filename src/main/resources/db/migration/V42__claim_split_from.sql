-- A claim split off another names the number of the claim it was split from; none for any other claim.
ALTER TABLE claim ADD COLUMN split_from VARCHAR(255);
