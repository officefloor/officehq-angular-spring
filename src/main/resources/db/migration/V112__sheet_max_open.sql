-- The sheet's maximum of OPEN or PENDING claims per person (not dated): opening a claim is refused when its
-- person already has that many. Null means none.
ALTER TABLE sheet ADD COLUMN max_open INTEGER;
