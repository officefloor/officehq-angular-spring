-- Whether the line was priced from the rates for its unit type (its client's rate card, or else the price list), at a
-- price already negotiated: it takes no share of the invoice discount. Unlike card_priced, sending keeps it.
ALTER TABLE invoice_line ADD COLUMN rate_priced BOOLEAN DEFAULT FALSE NOT NULL;
UPDATE invoice_line SET rate_priced = card_priced;
