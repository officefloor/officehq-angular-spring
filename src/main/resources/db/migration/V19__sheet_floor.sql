-- The sheet's floor: a claim's live due is at least it.
ALTER TABLE sheet ADD COLUMN due_floor DECIMAL(15, 2) DEFAULT 1.00 NOT NULL CHECK (due_floor >= 0);
