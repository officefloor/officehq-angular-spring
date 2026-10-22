-- The sheet a person was last transferred from; null when they were never transferred.
ALTER TABLE person ADD COLUMN origin_sheet_id VARCHAR(64);
