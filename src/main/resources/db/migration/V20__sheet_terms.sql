-- The sheet's terms: the number of days after a claim's date that it is due by.
ALTER TABLE sheet ADD COLUMN terms INT DEFAULT 14 NOT NULL;
