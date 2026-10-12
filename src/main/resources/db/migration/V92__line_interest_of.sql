-- The rebilled invoice whose late interest the line carries onto its replacement; null for an ordinary line. Such a
-- line takes no tax, no share of the invoice discount and no surcharge.
ALTER TABLE invoice_line ADD COLUMN interest_of BIGINT;
