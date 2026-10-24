-- The sheet's terms: how many days after its date a claim is due by. An OPEN claim whose due-by is
-- before the sheet's today is overdue.
ALTER TABLE sheet ADD COLUMN terms INTEGER DEFAULT 14 NOT NULL CHECK (terms >= 0);
