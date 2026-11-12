-- The sheet's floor: the least a claim's live due can be (two decimals). A sheet starts at 1.00.
ALTER TABLE sheet ADD COLUMN due_floor DECIMAL(15, 2) DEFAULT 1.00 NOT NULL CHECK (due_floor >= 0);
