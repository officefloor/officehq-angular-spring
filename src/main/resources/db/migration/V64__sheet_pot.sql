-- The sheet's pot (two decimals), cut among the members of each balanced pool by their shares. None means no pot.
-- Not dated.
ALTER TABLE sheet ADD COLUMN pot DECIMAL(14, 2);
