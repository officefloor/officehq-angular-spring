-- The sheet's floor: a claim's live due is at least it. Every sheet starts at 1.00.
ALTER TABLE sheet ADD COLUMN due_floor DECIMAL(14, 2) DEFAULT 1.00 NOT NULL;
