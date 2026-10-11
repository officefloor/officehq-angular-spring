-- The rush surcharge of a draft, a percentage (0 to 25) of its amount after its discounts.
ALTER TABLE invoice ADD COLUMN surcharge_pct DECIMAL(5, 2) DEFAULT 0 NOT NULL;
