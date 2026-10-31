-- A rate may be any percent of 0.00 or more up to 9999999.99, with two decimals.
ALTER TABLE rate ALTER COLUMN rate DECIMAL(9, 2);
ALTER TABLE rate_version ALTER COLUMN rate DECIMAL(9, 2);
