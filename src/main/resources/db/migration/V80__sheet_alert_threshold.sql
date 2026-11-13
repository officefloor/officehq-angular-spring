-- The sheet's alert threshold: a change records an ALERT line for each person whose due-total goes from at
-- or below it (or none) to above it. None means off. Not dated.
ALTER TABLE sheet ADD COLUMN alert_threshold NUMERIC(15,2) CHECK (alert_threshold >= 0);
