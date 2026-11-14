-- The score thresholds, whole numbers: while both are set each person shows a band, HIGH at or above
-- the high one, MID at or above the mid one, else LOW. Null (blank) is none.
ALTER TABLE sheet ADD COLUMN score_mid INTEGER;
ALTER TABLE sheet ADD COLUMN score_high INTEGER;
