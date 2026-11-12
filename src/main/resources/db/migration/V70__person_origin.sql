-- The sheet a person was transferred from; null for anyone added on their sheet.
ALTER TABLE person ADD COLUMN origin_sheet VARCHAR(200);
