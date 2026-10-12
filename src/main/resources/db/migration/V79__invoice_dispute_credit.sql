-- Whether the invoice's dispute was resolved with a credit note for what was in dispute: the interest the dispute
-- held back on the disputed amount, from disputed_on to dispute_resolved_on, is then never charged.
ALTER TABLE invoice ADD COLUMN dispute_credit BOOLEAN NOT NULL DEFAULT FALSE;
