-- The sheet's cap: a person's due-total is at most it; null when there is none.
ALTER TABLE sheet ADD COLUMN due_cap DECIMAL(15, 2) CHECK (due_cap >= 0);
