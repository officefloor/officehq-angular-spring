-- An OPEN claim may be split: the new claim, OPEN with the split-off amount, names the number of the
-- claim it was split from. Null on any other claim (the original names nothing).
ALTER TABLE claim ADD COLUMN split_from VARCHAR(220);
