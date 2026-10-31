-- A person's own terms in days: a claim of theirs is due by its date plus these, ahead of their grade's and the
-- sheet's terms; null when they have none.
ALTER TABLE person ADD COLUMN terms INT;
