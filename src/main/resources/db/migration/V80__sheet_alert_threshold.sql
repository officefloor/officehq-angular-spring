-- The sheet's alert threshold: an amount with two decimals; a change that takes a person's
-- due-total from at or below it to above it writes an ALERT line. Null when blank (off). Not dated.
ALTER TABLE sheet ADD COLUMN alert_threshold DECIMAL(15, 2);
