-- The grand total an invoice was approved at, when it was over the workspace's approval limit; the
-- approval stands while its grand total is not above it. Null while it has not been approved.
ALTER TABLE invoice ADD COLUMN approved_total DECIMAL(19, 2);
