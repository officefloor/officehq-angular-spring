-- The sheet's score log switch: while on, changing the sheet's today records a SCORE line of each person's
-- score on the day being left. Not dated.
ALTER TABLE sheet ADD COLUMN score_log BOOLEAN NOT NULL DEFAULT FALSE;
