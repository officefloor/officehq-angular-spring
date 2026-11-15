-- The sheet's pot: an amount with two decimals shared out among each balanced pool's members by
-- their shares; null when blank (none). Not dated.
ALTER TABLE sheet ADD COLUMN pot DECIMAL(15, 2);
