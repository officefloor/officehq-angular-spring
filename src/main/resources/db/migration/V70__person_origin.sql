-- The sheet a transferred person came from; null for anyone not transferred in.
ALTER TABLE person ADD COLUMN origin_sheet VARCHAR(100);
