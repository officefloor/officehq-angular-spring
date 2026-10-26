-- The most a person's due-total can be; null means no cap.
ALTER TABLE sheet ADD COLUMN due_cap DECIMAL(14, 2) CHECK (due_cap >= 0);
