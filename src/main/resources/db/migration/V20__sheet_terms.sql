-- The sheet's terms: the number of days after its date a claim is due by. An OPEN claim whose
-- due-by is before the sheet's today is overdue.
ALTER TABLE sheet ADD COLUMN terms INT DEFAULT 14 NOT NULL;
