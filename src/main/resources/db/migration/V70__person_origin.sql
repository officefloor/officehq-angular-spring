-- A transferred person keeps the id of the sheet they came from; null for anyone else.
ALTER TABLE person ADD COLUMN origin_sheet VARCHAR(100);
