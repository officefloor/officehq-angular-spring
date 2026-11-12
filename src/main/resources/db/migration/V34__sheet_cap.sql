-- The sheet's cap: the most a person's due-total can be (two decimals); none means no cap.
ALTER TABLE sheet ADD COLUMN due_cap DECIMAL(15, 2) CHECK (due_cap >= 0);
