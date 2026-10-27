-- A transferred person keeps the id of the sheet they came from; null: not transferred.
ALTER TABLE person ADD COLUMN origin_sheet VARCHAR(100);
