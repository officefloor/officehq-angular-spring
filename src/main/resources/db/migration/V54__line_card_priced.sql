-- Whether the line's price comes from its client's rate card for its unit type, so a draft's line follows the
-- card when a rate is changed; sending fixes the price on the line and clears this.
ALTER TABLE invoice_line ADD COLUMN card_priced BOOLEAN DEFAULT FALSE NOT NULL;
