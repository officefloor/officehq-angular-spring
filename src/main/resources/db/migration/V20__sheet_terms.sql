-- The sheet's terms: the number of days after its date that a claim is due by. A sheet starts with terms of 14.
ALTER TABLE sheet ADD COLUMN terms INT DEFAULT 14 NOT NULL CHECK (terms >= 0);
