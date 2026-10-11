-- The id of the invoice line a credit note's line credits; NULL for a line that credits no line in particular.
ALTER TABLE invoice_line ADD COLUMN credit_of_line BIGINT;
