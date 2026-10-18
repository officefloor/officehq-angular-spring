-- The sheet's terms: a claim is due by its date plus this many days. It starts at 14.
ALTER TABLE sheet ADD COLUMN terms INT DEFAULT 14 NOT NULL;
