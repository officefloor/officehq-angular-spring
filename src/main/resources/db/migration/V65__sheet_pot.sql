-- The sheet's pot: shared out over each balanced pool's members by their shares. Not dated; null means none.
ALTER TABLE sheet ADD COLUMN pot DECIMAL(12, 2) CHECK (pot >= 0);
