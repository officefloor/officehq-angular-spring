-- The sheet's pot, shared out among the members of each balanced pool by their shares; null when
-- there is none. Not dated.
ALTER TABLE sheet ADD COLUMN pot DECIMAL(12, 2) CHECK (pot >= 0);
