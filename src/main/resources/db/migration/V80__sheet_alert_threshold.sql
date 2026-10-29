-- The sheet's alert threshold: a person whose due-total crosses above it gets an ALERT line; null (blank) for off.
ALTER TABLE sheet ADD COLUMN alert_threshold NUMERIC(14, 2) CHECK (alert_threshold >= 0);
