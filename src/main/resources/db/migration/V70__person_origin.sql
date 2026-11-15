-- The id of the sheet a person was transferred from; null for anyone not transferred here.
ALTER TABLE person ADD COLUMN origin_sheet_id VARCHAR(255);
