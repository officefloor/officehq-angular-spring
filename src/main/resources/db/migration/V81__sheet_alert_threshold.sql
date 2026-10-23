-- The sheet's alert threshold: a person whose due-total crosses above it is alerted. Null means off; not dated.
ALTER TABLE sheet ADD COLUMN alert_threshold DECIMAL(14, 2);
