-- The sheet's cap: a person's due-total is at most it, the rest their excess. Null: no cap.
ALTER TABLE sheet ADD COLUMN due_cap DECIMAL(14, 2) CHECK (due_cap >= 0);
