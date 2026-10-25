-- Whether the sheet logs scores: while it is on, changing the sheet's today first records a SCORE line
-- per person, in list order, with their score on the day being left.
ALTER TABLE sheet ADD COLUMN score_log BOOLEAN NOT NULL DEFAULT FALSE;
