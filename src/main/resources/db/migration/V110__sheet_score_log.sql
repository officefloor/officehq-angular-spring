-- The sheet's score log switch: while on, a change of the sheet's today first records a SCORE line per person with
-- their score on the day being left. Off by default. Not dated.
ALTER TABLE sheet ADD COLUMN score_log BOOLEAN NOT NULL DEFAULT FALSE;
