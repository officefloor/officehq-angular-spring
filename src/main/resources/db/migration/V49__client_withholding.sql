-- The percentage (0 to 100, 0 is none) of the amount before tax the client withholds from each invoice
-- and pays to its tax office instead; it is never owed by the client.
ALTER TABLE client ADD COLUMN withholding_pct DECIMAL(5, 2) DEFAULT 0 NOT NULL;
