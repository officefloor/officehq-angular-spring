-- The sheet's maximum of open claims per person: a claim is not opened when its person already has this many
-- OPEN or PENDING claims. None means none. Not dated.
ALTER TABLE sheet ADD COLUMN max_open INTEGER CHECK (max_open >= 0);
