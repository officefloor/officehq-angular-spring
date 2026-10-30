-- The sheet's today when the person arrived by transfer; null for a person who was not transferred here.
ALTER TABLE person ADD COLUMN arrived_on DATE;
