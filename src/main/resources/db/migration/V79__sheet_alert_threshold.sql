-- The sheet's alert threshold (two decimals): a person whose due-total rises from at or below it to above it is
-- recorded with an ALERT line. None means off. Not dated.
ALTER TABLE sheet ADD COLUMN alert_threshold DECIMAL(14, 2);
