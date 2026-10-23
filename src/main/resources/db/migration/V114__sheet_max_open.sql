-- The most OPEN or PENDING claims a person may have: opening one more (alone, in bulk or by a paste)
-- is refused. Null means none; not dated.
ALTER TABLE sheet ADD COLUMN max_open INT;
