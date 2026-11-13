-- A sheet's floor (two decimals): a claim's live due is at least it. A sheet starts at 1.00.
ALTER TABLE sheet ADD COLUMN due_floor DECIMAL(12, 2) DEFAULT 1.00 NOT NULL;
