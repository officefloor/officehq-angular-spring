-- A dated setting whose value is a word rather than a figure (the rounding mode: half-up or
-- half-even) keeps it here, its figure left null; null in both means none.
ALTER TABLE sheet_setting_value ADD COLUMN setting_text VARCHAR(20);
ALTER TABLE sheet_setting_value_history ADD COLUMN setting_text VARCHAR(20);
