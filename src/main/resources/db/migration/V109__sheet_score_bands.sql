-- The score thresholds (not dated, whole numbers): while both are set, a person whose score is at or
-- above the high one is in band HIGH, at or above the mid one MID, else LOW. Null means blank.
ALTER TABLE sheet ADD COLUMN score_mid INTEGER;
ALTER TABLE sheet ADD COLUMN score_high INTEGER;
