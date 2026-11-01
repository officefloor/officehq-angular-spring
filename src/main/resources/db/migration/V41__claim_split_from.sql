-- A claim split off an OPEN claim names, by number, the claim it was split from. Empty for any other claim.
ALTER TABLE claim ADD COLUMN split_from VARCHAR(220);
