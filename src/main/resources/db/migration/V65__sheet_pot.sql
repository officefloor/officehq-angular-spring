-- The sheet's pot: shared out among the members of each balanced pool by their shares. None means no pot.
-- Not dated.
ALTER TABLE sheet ADD COLUMN pot NUMERIC(15,2) CHECK (pot >= 0);
