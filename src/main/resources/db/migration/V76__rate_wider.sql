-- A rate may be any percent of 0.00 or more with up to seven digits before the decimals.
ALTER TABLE rate ALTER COLUMN pct DECIMAL(9, 2);
ALTER TABLE rate_change ALTER COLUMN pct DECIMAL(9, 2);
