-- The sheet's pot: the amount a balanced pool's members cut by their shares; null (blank) is none.
ALTER TABLE sheet ADD COLUMN pot DECIMAL(14, 2);
