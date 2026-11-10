-- The sheet's today when a transferred person arrived; null for anyone not transferred in.
ALTER TABLE person ADD COLUMN arrived_on DATE;
