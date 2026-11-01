-- The sheet's alert threshold (not dated): a change that takes a person's due-total from at or below
-- it (or none) to above it audits an ALERT line for them. Null means off.
ALTER TABLE sheet ADD COLUMN alert_threshold DECIMAL(14, 2);
