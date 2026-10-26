-- A person may have their own terms: a claim of theirs is due by its date plus these days, before their grade's terms
-- and the sheet's. Null: none of their own.
ALTER TABLE person ADD COLUMN terms INT CHECK (terms >= 0);
