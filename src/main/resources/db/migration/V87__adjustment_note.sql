-- The id of the sent invoice this adjustment note (debit or credit) adjusts; null for any other invoice.
ALTER TABLE invoice ADD COLUMN adjustment_of BIGINT;
