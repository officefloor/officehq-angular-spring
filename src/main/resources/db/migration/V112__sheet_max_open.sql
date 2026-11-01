-- The sheet's maximum of open claims per person, a whole number: opening a claim is refused when its person already
-- has this many OPEN or PENDING claims. None means none. Not dated.
ALTER TABLE sheet ADD COLUMN max_open INT;
