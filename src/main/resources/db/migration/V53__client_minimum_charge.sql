-- The client's own minimum charge, in the client's currency (no conversion), charged instead of the
-- workspace's on its invoices; NULL for the workspace's, 0 for never charged a minimum.
ALTER TABLE client ADD COLUMN minimum_charge DECIMAL(19, 2);
