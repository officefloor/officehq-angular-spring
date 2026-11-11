-- A rate is a percent from 0.00 to 99999.99, so a rate (and the versions and log kept of it) holds up to five whole
-- digits.
ALTER TABLE rate ALTER COLUMN pct DECIMAL(7, 2);
ALTER TABLE rate_version ALTER COLUMN pct DECIMAL(7, 2);
ALTER TABLE rate_log ALTER COLUMN pct DECIMAL(7, 2);
