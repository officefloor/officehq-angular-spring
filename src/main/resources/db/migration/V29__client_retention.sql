-- The standard retention (0 to 20 percent, 0 is none) new invoices for the client start with.
ALTER TABLE client ADD COLUMN retention_pct DECIMAL(5, 2) DEFAULT 0 NOT NULL;
