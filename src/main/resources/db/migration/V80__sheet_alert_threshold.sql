-- The sheet's alert threshold (none: off): a person whose due-total rises above it is alerted.
ALTER TABLE sheet ADD COLUMN alert_threshold DECIMAL(14, 2) CHECK (alert_threshold >= 0);
