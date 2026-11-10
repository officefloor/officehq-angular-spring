-- A person's own terms, in days; null when they have none. A claim's due-by takes them first, before
-- the terms of the person's grade and the sheet's.
ALTER TABLE person ADD COLUMN terms INTEGER CHECK (terms >= 0);
