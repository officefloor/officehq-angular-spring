-- The sheet a transferred person came from; null for anyone else.
ALTER TABLE person ADD COLUMN origin_sheet_id VARCHAR(100);
