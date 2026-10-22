-- A person's own terms in days; null when the person has none and takes their grade's (or the
-- sheet's) terms. A claim's due-by is fixed when it is added from the first of the person's own
-- terms, their grade's terms and the sheet's terms in force on its date.
ALTER TABLE person ADD COLUMN terms INT CHECK (terms >= 0);
