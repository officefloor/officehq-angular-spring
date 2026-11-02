-- The sheet's today when a user started watching a person; null for one started before it was kept
-- (watched all along).
ALTER TABLE person_watch ADD COLUMN started_on DATE;
