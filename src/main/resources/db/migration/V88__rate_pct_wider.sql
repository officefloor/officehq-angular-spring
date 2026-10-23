-- A rate's percent may be up to five digits before its two decimals.
ALTER TABLE rate ALTER COLUMN pct SET DATA TYPE DECIMAL(7, 2);
ALTER TABLE rate_version ALTER COLUMN pct SET DATA TYPE DECIMAL(7, 2);
