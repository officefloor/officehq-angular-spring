-- The share of the line's work done, more than 0 and at most 100 with up to two decimals, for a line billed by progress:
-- its amount is that percentage of its full amount, rounded half up to the cent. Null bills the whole line.
ALTER TABLE invoice_line ADD COLUMN percent_complete DECIMAL(5, 2);
