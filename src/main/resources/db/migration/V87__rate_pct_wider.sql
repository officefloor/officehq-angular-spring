-- A rate takes up to seven digits before its two decimals (a rate above 999.99 percent is kept).
ALTER TABLE rate ALTER COLUMN pct DECIMAL(9, 2);
ALTER TABLE rate_version ALTER COLUMN pct DECIMAL(9, 2);
ALTER TABLE rate_earlier ALTER COLUMN pct DECIMAL(9, 2);
