-- The sheet a person was transferred from; null for anyone not transferred.
ALTER TABLE person ADD COLUMN origin VARCHAR(100);
