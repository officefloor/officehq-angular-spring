-- The sheet's today on which a person arrived on it by a transfer; null when they did not arrive by
-- one (a transfer that merged them into someone there leaves that person's as it was).
ALTER TABLE person ADD COLUMN arrived_on DATE;
