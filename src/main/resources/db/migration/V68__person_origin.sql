-- The sheet a person was transferred from; null for a person who was not transferred.
ALTER TABLE person ADD COLUMN origin VARCHAR(100);
