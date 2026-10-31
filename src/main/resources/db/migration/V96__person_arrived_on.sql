-- The sheet's today on the sheet a person was last transferred to, the day they arrived; null for anyone never
-- transferred.
ALTER TABLE person ADD COLUMN arrived_on DATE;
