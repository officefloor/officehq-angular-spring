-- The sheet's maximum of open claims per person: opening a claim is refused when its person already
-- has this many OPEN or PENDING claims; null (blank) is none.
ALTER TABLE sheet ADD COLUMN max_open INTEGER;
