-- The sheet's alert threshold: a person whose due-total goes above it gets an ALERT line in the
-- audit; null when it is off. Not dated.
ALTER TABLE sheet ADD COLUMN alert_threshold DECIMAL(12, 2) CHECK (alert_threshold >= 0);
