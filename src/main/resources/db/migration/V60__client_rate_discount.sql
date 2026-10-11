-- The percentage (0 to 100, 0 is none) taken off every price a line of the client's takes from its rate card or
-- the price list, each band's price included, per unit after conversion and before overtime.
ALTER TABLE client ADD COLUMN rate_discount_pct DECIMAL(5, 2) DEFAULT 0 NOT NULL;
