-- Score thresholds: while both are set, a person whose score is at or above the high one is HIGH, at or
-- above the mid one MID, else LOW. Whole numbers; null when blank (no band). Not dated.
ALTER TABLE sheet ADD COLUMN score_mid INT;
ALTER TABLE sheet ADD COLUMN score_high INT;
