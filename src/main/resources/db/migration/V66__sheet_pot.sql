-- The sheet's pot (not dated): split across each balanced pool's members as their cuts. Null means
-- none.
ALTER TABLE sheet ADD COLUMN pot DECIMAL(14, 2);
