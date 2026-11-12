-- The sheet's maximum of OPEN or PENDING claims each person may have; opening one more is refused.
-- Not dated; null means none.
ALTER TABLE sheet ADD COLUMN max_open INTEGER CHECK (max_open >= 0);
