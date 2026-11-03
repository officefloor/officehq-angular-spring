-- The sheet's terms: a claim is due by its date plus this many days. 14 on a new sheet.
ALTER TABLE sheet ADD COLUMN terms INTEGER DEFAULT 14 NOT NULL CHECK (terms >= 0);
