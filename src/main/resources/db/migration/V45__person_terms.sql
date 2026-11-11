-- A person's own terms: the days after its date a claim of the person is due by, ahead of their grade's and the
-- sheet's terms. Null: the person has none.
ALTER TABLE person ADD COLUMN terms INT CHECK (terms >= 0);
