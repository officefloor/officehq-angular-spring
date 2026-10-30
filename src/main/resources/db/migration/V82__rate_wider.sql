-- A rate may run past 999.99 percent: up to twelve digits before the decimals, as other figures do.
ALTER TABLE rate ALTER COLUMN pct DECIMAL(14, 2);
ALTER TABLE rate_version ALTER COLUMN pct DECIMAL(14, 2);
