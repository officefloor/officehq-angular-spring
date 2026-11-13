-- An OPEN claim can be split: the new claim it splits off names, by number, the claim it was split
-- from; null for any claim that was not split off another.
ALTER TABLE claim ADD COLUMN split_from VARCHAR(255);
