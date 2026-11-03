-- The sheet's floor: a claim's live due is never below it. Settled, stated and closed figures keep theirs.
ALTER TABLE sheet ADD COLUMN due_floor DECIMAL(14, 2) DEFAULT 1.00 NOT NULL CHECK (due_floor >= 0);
