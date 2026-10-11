-- A client's standard percentage discount, 0 to 100 with up to two decimals, that new invoices
-- for the client start with; 0 is none.
ALTER TABLE client ADD COLUMN discount_pct DECIMAL(5, 2) DEFAULT 0 NOT NULL;
