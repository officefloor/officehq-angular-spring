-- The region a client is in (a state, say): its invoices are taxed at the region's rate from the
-- workspace's regional rates (region_tax_rate_<region> settings); null is no region.
ALTER TABLE client ADD COLUMN region VARCHAR(64);
