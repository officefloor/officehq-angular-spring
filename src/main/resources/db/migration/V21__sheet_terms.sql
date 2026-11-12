-- The sheet's terms: the number of days after a claim's date it is due by. A sheet starts at 14.
ALTER TABLE sheet ADD COLUMN terms INT DEFAULT 14 NOT NULL CHECK (terms >= 0);
