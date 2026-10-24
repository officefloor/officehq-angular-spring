-- The most a person's due-total can be: a sum of their figures above it shows the cap, and the
-- difference as their excess. Null means no cap.
ALTER TABLE sheet ADD COLUMN due_cap DECIMAL(12, 2) CHECK (due_cap >= 0);
