-- The client's tax number: two letters then ten digits, the last two a check on the first eight.
-- A client with a valid number outside the workspace's home region (the home_region setting) is
-- reverse charged: its invoices carry no tax. Null is no number.
ALTER TABLE client ADD COLUMN tax_number VARCHAR(64);
