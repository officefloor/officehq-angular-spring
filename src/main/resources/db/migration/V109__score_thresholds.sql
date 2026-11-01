-- The sheet's score thresholds, whole numbers: while both are set each person shows a band, HIGH at or above the
-- high one, MID at or above the mid one, else LOW. None means none. Not dated.
ALTER TABLE sheet ADD COLUMN score_mid INT;
ALTER TABLE sheet ADD COLUMN score_high INT;
