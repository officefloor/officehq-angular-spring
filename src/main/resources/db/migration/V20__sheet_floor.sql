-- A sheet's floor: the least a claim's live due comes to; it starts at 1.00.
ALTER TABLE sheet ADD COLUMN due_floor DECIMAL(12, 2) DEFAULT 1.00 NOT NULL CHECK (due_floor >= 0);
