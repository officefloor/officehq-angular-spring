-- The sheet's cap: a person's due-total is at most it, the rest showing as their excess. A due-total of n/a is not
-- capped. A sheet starts with no cap (null).
ALTER TABLE sheet ADD COLUMN due_cap DECIMAL(12, 2);
