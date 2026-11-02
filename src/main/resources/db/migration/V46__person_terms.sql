-- A person's own terms in days; null when they have none and take their grade's or the sheet's.
-- A claim's due-by is fixed from them first when it is made.
ALTER TABLE person ADD COLUMN terms INT;
