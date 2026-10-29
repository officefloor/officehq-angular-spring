-- The sheet's maximum of open claims per person: opening a claim is refused while the person already has this many
-- OPEN or PENDING claims; null (blank) for none. Not dated.
ALTER TABLE sheet ADD COLUMN max_open INTEGER CHECK (max_open >= 0);
