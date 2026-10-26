-- The least a claim's live due can be; it starts at 1.00.
ALTER TABLE sheet ADD COLUMN due_floor DECIMAL(14, 2) DEFAULT 1.00 NOT NULL CHECK (due_floor >= 0);
