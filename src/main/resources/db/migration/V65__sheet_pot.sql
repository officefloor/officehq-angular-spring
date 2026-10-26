-- The sheet's pot, shared among the members of each balanced pool by their shares; null (blank) for none.
ALTER TABLE sheet ADD COLUMN pot NUMERIC(14, 2) CHECK (pot >= 0);
