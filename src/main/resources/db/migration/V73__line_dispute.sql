-- Whether the line is in the invoice's dispute (from the invoice's disputed_on until it is resolved): only the
-- disputed lines then earn no interest and are not overdue. An open dispute with no line in it covers the whole invoice.
ALTER TABLE invoice_line ADD COLUMN disputed BOOLEAN NOT NULL DEFAULT FALSE;
