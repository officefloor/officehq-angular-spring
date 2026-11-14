-- A sheet's alert threshold (two decimals), not dated: a person whose due-total crosses above it
-- after a change writes an ALERT line. Null (blank) is off.
ALTER TABLE sheet ADD COLUMN alert_threshold DECIMAL(19, 2);
