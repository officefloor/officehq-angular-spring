-- The sheet's terms in days: a claim's due-by is its date plus them. Every sheet starts at 14.
ALTER TABLE sheet ADD COLUMN terms INT DEFAULT 14 NOT NULL;
