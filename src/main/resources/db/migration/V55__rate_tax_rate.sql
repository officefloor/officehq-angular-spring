-- A rate card entry's own tax percentage: lines of its unit type without a rate of their own are taxed at it
-- instead of the client's regional or workspace rate; NULL for none.
ALTER TABLE client_rate ADD COLUMN tax_rate DECIMAL(5, 2);
