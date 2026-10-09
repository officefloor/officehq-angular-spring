-- The number of decimal places each currency's amounts are shown with (yen has none); existing
-- currencies keep two.
ALTER TABLE currency ADD COLUMN decimals INT DEFAULT 2 NOT NULL;
ALTER TABLE currency ADD CONSTRAINT currency_decimals_range CHECK (decimals BETWEEN 0 AND 4);
