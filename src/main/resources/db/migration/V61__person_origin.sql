-- The sheet a person was transferred from; null for a person added on their sheet.
ALTER TABLE person ADD COLUMN origin_sheet_id VARCHAR(100);
