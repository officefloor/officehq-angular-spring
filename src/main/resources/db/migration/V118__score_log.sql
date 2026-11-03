-- The sheet's score log switch: on or off (blank), not dated. While it is on, changing the sheet's
-- today records each person's score on the day being left, before their snapshots.
ALTER TABLE sheet ADD COLUMN score_log BOOLEAN NOT NULL DEFAULT FALSE;
