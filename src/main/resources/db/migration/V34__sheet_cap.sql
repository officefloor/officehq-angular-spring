-- The sheet's cap: a person's due-total is at most it, the rest shown as their excess. Null means
-- none.
ALTER TABLE sheet ADD COLUMN due_cap DECIMAL(14, 2);
