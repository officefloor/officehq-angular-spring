-- The most OPEN or PENDING claims a person may have, a whole number, not dated: opening one more is
-- refused. Null (blank) is none.
ALTER TABLE sheet ADD COLUMN max_open INTEGER;
