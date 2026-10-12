-- The grand total and tax an invoice was sent with; the invoice is worth exactly that once sent.
-- Null for a draft, or for a sent invoice whose figures were not kept (worth what its lines work out to).
ALTER TABLE invoice ADD COLUMN sent_total DECIMAL(19, 2);
ALTER TABLE invoice ADD COLUMN sent_tax DECIMAL(19, 2);
