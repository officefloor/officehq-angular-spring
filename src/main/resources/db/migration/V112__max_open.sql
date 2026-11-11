-- The most claims a person may have OPEN or PENDING at once on the sheet (none: no limit). Not dated.
ALTER TABLE sheet ADD COLUMN max_open INTEGER CHECK (max_open >= 0);
