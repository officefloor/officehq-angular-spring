-- The sheet's terms: a claim is due by its date plus this many days.
ALTER TABLE sheet ADD COLUMN terms INT DEFAULT 14 NOT NULL CHECK (terms >= 0);
