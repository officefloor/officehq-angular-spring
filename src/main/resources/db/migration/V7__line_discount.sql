-- A line's own percentage discount off its quantity times unit price, 0 to 100 with up to two decimals; 0 is none.
ALTER TABLE invoice_line ADD COLUMN discount_pct DECIMAL(5, 2) DEFAULT 0 NOT NULL;
