-- The sheet's score thresholds, whole numbers: a person whose score is at or above the high one is
-- HIGH, at or above the mid one MID, else LOW. Null means not set; no band shows while either is.
ALTER TABLE sheet ADD COLUMN score_mid INTEGER;
ALTER TABLE sheet ADD COLUMN score_high INTEGER;
