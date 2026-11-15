-- The sheet's score thresholds: while both are set, each person shows a band, HIGH at or above the
-- high one, MID at or above the mid one, else LOW; null when blank. Whole numbers, not dated.
ALTER TABLE sheet ADD COLUMN score_mid INTEGER;
ALTER TABLE sheet ADD COLUMN score_high INTEGER;
