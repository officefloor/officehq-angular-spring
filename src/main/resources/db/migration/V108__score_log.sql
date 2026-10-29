-- The sheet's score log switch: while on, changing the sheet's today writes a SCORE line per person with their score
-- on the day being left. False (blank) for off. Not dated.
ALTER TABLE sheet ADD COLUMN score_log BOOLEAN NOT NULL DEFAULT FALSE;
