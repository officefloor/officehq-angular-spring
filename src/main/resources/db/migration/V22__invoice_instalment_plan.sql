-- A draft spread over monthly instalments: how many (2 to 24; null when there is no plan) and the
-- annual interest rate (0 to 30, up to two decimals) the schedule is worked out at.
ALTER TABLE invoice ADD COLUMN instalments INT;
ALTER TABLE invoice ADD COLUMN instalment_rate DECIMAL(5, 2) DEFAULT 0 NOT NULL;
