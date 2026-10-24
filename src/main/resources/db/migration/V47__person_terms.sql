-- A person's own terms: how many days after its date a claim of theirs is due by, in place of the
-- terms of their grade and the sheet's; null when they have none.
ALTER TABLE person ADD COLUMN terms INTEGER CHECK (terms >= 0);
