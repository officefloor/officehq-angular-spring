-- The number of the claim an OPEN claim was split from; none for a claim that was not split off.
ALTER TABLE claim ADD COLUMN split_from VARCHAR(255);
