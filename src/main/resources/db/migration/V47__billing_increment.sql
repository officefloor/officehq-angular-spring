-- The increment a rate card entry's unit type is billed in (0.25 of an hour, packs of 5): a line of
-- that unit type is billed on its quantity rounded up to the next whole increment; NULL for none.
ALTER TABLE client_rate ADD COLUMN increment DECIMAL(19, 2);

-- The increment the line is billed in, from its client's rate card for its unit type; NULL for none.
ALTER TABLE invoice_line ADD COLUMN billing_increment DECIMAL(19, 2);
