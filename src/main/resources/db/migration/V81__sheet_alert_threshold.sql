-- The sheet's alert threshold: a person whose due-total goes above it is reported in an ALERT line. Not dated; null means off.
ALTER TABLE sheet ADD COLUMN alert_threshold DECIMAL(12, 2) CHECK (alert_threshold >= 0);
