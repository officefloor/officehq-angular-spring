-- The sheet's alert threshold: a person whose due-total crosses above it is alerted; null (blank) is off.
ALTER TABLE sheet ADD COLUMN alert_threshold DECIMAL(14, 2);
