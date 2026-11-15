-- The sheet's score log switch: while on, changing the sheet's today records each person's score
-- on the day being left (SCORE). Off unless set; not dated.
ALTER TABLE sheet ADD COLUMN score_log BOOLEAN NOT NULL DEFAULT FALSE;
