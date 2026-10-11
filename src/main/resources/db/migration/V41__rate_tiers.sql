-- A rate card entry priced in progressive tiers instead of one price: its bands as
-- "units:unitPrice" joined by semicolons, the last band with no units (";:12.00"); NULL for a single price.
ALTER TABLE client_rate ALTER COLUMN unit_price SET NULL;
ALTER TABLE client_rate ADD COLUMN tiers VARCHAR(1024);

-- The tiers a line was priced in from the rate card, in the same form; NULL when it has a single unit price.
ALTER TABLE invoice_line ADD COLUMN tiers VARCHAR(1024);
