-- The sheet's floor: a claim's live due is at least it. A settled claim's frozen due, a due bound by a statement
-- and a closed period's fixed total are left as they are. A sheet starts with a floor of 1.00.
ALTER TABLE sheet ADD COLUMN due_floor DECIMAL(12, 2) DEFAULT 1.00 NOT NULL;
