-- The least a claim's live due can be: a due worked out at a rate below it is raised to it. A
-- figure already fixed (settled, bound by a statement, a closed period's total) is left as it was.
ALTER TABLE sheet ADD COLUMN due_floor DECIMAL(12, 2) DEFAULT 1.00 NOT NULL CHECK (due_floor >= 0);
