-- The sheet's score thresholds: while both are set, each person shows a band — HIGH at or above score_high,
-- MID at or above score_mid, else LOW. None means no bands. Whole numbers, not dated.
ALTER TABLE sheet ADD COLUMN score_mid INTEGER;
ALTER TABLE sheet ADD COLUMN score_high INTEGER;
