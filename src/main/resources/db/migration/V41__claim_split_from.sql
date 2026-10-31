-- A claim split off another names, by number, the claim it was split from. None on any other claim (the claim
-- split keeps none).
ALTER TABLE claim ADD COLUMN split_from VARCHAR(120);
