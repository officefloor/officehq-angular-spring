-- A sheet's cap: the most a person's due-total comes to, with two decimals; none (null) means no cap.
ALTER TABLE sheet ADD COLUMN cap DECIMAL(12, 2) CHECK (cap >= 0);
