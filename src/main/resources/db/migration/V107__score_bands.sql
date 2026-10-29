-- The sheet's score thresholds: while both are set, a person whose score is at or above the high one is HIGH, at or
-- above the mid one MID, else LOW; null (blank) for none. Whole numbers, not dated.
ALTER TABLE sheet ADD COLUMN score_mid INTEGER;
ALTER TABLE sheet ADD COLUMN score_high INTEGER;
