-- A sheet's pot (two decimals), not dated: each member of a balanced pool shows its cut of it. Null
-- (blank) is none.
ALTER TABLE sheet ADD COLUMN pot DECIMAL(19, 2);
