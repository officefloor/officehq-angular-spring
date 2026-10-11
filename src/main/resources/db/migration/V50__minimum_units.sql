-- The minimum units a rate card entry's unit type is billed (a call-out is at least 2 hours): a line of
-- that unit type is billed at least that many units, after rounding up to its increment; NULL for none.
ALTER TABLE client_rate ADD COLUMN min_units DECIMAL(19, 2);

-- The minimum units the line is billed, from its client's rate card for its unit type; NULL for none.
ALTER TABLE invoice_line ADD COLUMN billing_min_units DECIMAL(19, 2);
