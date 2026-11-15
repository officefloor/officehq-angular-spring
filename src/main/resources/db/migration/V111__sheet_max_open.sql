-- The most OPEN or PENDING claims a person may have: opening one more is refused; null when blank
-- (none). Not dated.
ALTER TABLE sheet ADD COLUMN max_open INTEGER;
