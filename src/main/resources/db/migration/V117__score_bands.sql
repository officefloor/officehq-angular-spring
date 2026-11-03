-- The sheet's score thresholds: whole numbers, not dated. While both are set each person shows a
-- band by their score (HIGH at or above the high one, MID at or above the mid one, else LOW);
-- null (blank) shows none.
ALTER TABLE sheet ADD COLUMN score_mid INTEGER;
ALTER TABLE sheet ADD COLUMN score_high INTEGER;
