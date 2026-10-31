-- The sheet a person was last transferred from; null for anyone never transferred.
ALTER TABLE person ADD COLUMN origin_sheet_id VARCHAR(100);
