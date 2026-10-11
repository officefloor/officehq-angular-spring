-- The region where a draft's work is done (the place of supply): the invoice is taxed, and reverse
-- charged, as for that region instead of the client's; null for the client's region.
ALTER TABLE invoice ADD COLUMN supply_region VARCHAR(50);
