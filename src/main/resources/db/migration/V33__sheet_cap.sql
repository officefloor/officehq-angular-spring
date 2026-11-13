-- A sheet's cap (two decimals): a person's due-total is at most it. A sheet starts with none (null).
ALTER TABLE sheet ADD COLUMN due_cap DECIMAL(12, 2);
